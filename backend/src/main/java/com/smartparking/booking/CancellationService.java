package com.smartparking.booking;

import com.smartparking.booking.CancellationPolicyCalculator.Result;
import com.smartparking.booking.dto.BookingDetailDto;
import com.smartparking.booking.dto.CancellationPreview;
import com.smartparking.booking.dto.OwnerBookingDto;
import com.smartparking.common.config.AppProperties;
import com.smartparking.common.error.ApiException;
import com.smartparking.earning.EarningStatus;
import com.smartparking.earning.OwnerEarningRepository;
import com.smartparking.email.EmailTemplates;
import com.smartparking.listing.CancellationPolicy;
import com.smartparking.listing.ParkingListing;
import com.smartparking.notification.NotificationType;
import com.smartparking.notification.Notifier;
import com.smartparking.payment.Payment;
import com.smartparking.payment.PaymentRepository;
import com.smartparking.payment.PaymentStatus;
import com.smartparking.payment.RefundNotice;
import com.smartparking.payment.RefundService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cancellations by the driver (with a preview of the refund) and by the owner.
 *
 * <p>A cancellation locks the payment row and then the booking row ({@link BookingLocks}), re-checks the status on the
 * locked row, applies the transition and the refund in one transaction, and sends emails only after it commits. The
 * preview and the cancellation decide with the same code, so what the driver is shown is what the cancellation does.
 */
@Service
@RequiredArgsConstructor
public class CancellationService {

    static final String NOT_CANCELLABLE = "NOT_CANCELLABLE";
    static final String STARTED = "Bookings can't be cancelled once they've started";
    private static final String OWNER_PATH = "/owner/bookings";
    private static final String DRIVER_REASON = "Cancelled by driver";

    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final BookingLocks locks;
    private final BookingEvents events;
    private final RefundService refunds;
    private final OwnerEarningRepository earnings;
    private final BookingMapper mapper;
    private final Notifier notifier;
    private final AppProperties app;
    private final Clock clock;

    // ---- driver ---------------------------------------------------------------------------------------------

    /** What cancelling the driver's booking would do right now. */
    @Transactional(readOnly = true)
    public CancellationPreview preview(Long driverId, Long bookingId) {
        Booking booking = bookings.findByIdAndDriverId(bookingId, driverId)
                .orElseThrow(() -> ApiException.notFound("Booking not found"));
        return driverOutcome(booking, clock.instant());
    }

    /** Cancels the driver's booking and refunds what the policy allows; 409 {@code NOT_CANCELLABLE} when it can't be. */
    @Transactional
    public BookingDetailDto cancelByDriver(Long driverId, Long bookingId, String reason) {
        if (!bookings.existsByIdAndDriverId(bookingId, driverId)) {
            throw ApiException.notFound("Booking not found");
        }
        Booking booking = locks.lock(bookingId);
        CancellationPreview outcome = driverOutcome(booking, clock.instant());
        if (!outcome.cancellable()) {
            throw ApiException.conflict(NOT_CANCELLABLE, outcome.reason());
        }
        BookingStatus from = booking.getStatus();
        cancel(booking, BookingActor.DRIVER, reason, reason != null ? reason : DRIVER_REASON);

        Payment payment = payments.findByBookingId(bookingId).orElse(null);
        if (from == BookingStatus.PENDING_PAYMENT) {
            if (payment != null && payment.getStatus() == PaymentStatus.CREATED) {
                payment.setStatus(PaymentStatus.FAILED);
                payment.setFailureReason(DRIVER_REASON);
            }
        } else if (outcome.refundAmount().signum() > 0) {
            refunds.refund(booking, payment, outcome.refundAmount(), BookingActor.DRIVER, "Booking cancelled by driver",
                    RefundNotice.CANCELLATION);
        }

        releaseRetainedEarning(booking);

        String refundLine = driverRefundLine(from, outcome);
        String driverPath = BookingPaths.driver(booking);
        ParkingListing listing = booking.getListing();
        notifier.notify(booking.getDriver(), NotificationType.BOOKING_CANCELLED, "Booking cancelled",
                "Your booking " + booking.getBookingCode() + " at " + listing.getTitle() + " was cancelled. "
                        + refundLine, driverPath,
                EmailTemplates.bookingCancelled(booking.getDriver(), booking, refundLine, link(driverPath)));
        if (from != BookingStatus.PENDING_PAYMENT) { // owners never see unpaid holds
            notifier.notify(listing.getOwner(), NotificationType.OWNER_BOOKING_CANCELLED, "Booking cancelled",
                    "Booking " + booking.getBookingCode() + " for " + listing.getTitle()
                            + " was cancelled by the driver.", OWNER_PATH,
                    EmailTemplates.bookingCancelledByDriver(listing.getOwner(), booking, reason, link(OWNER_PATH)));
        }
        return mapper.toDetail(booking);
    }

    /**
     * A driver cancellation is final: whatever the refunds left of the owner's earning (the share the policy keeps) is
     * payable now. Nothing else would ever move it on, as the lifecycle job only completes confirmed bookings. Earnings
     * with nothing left stay reversed, and paid-out or reversed ones are never touched.
     */
    private void releaseRetainedEarning(Booking booking) {
        earnings.findByBookingId(booking.getId()).ifPresent(earning -> {
            if (earning.getStatus() == EarningStatus.HELD && earning.getNet().signum() > 0) {
                earning.setStatus(EarningStatus.PENDING_PAYOUT);
            }
        });
    }

    /**
     * The driver's options at {@code now}: unpaid holds and requests nobody accepted are given up freely (the latter
     * refunded in full), a confirmed booking that has not started follows the listing's policy, anything else
     * can't be cancelled.
     */
    private CancellationPreview driverOutcome(Booking booking, Instant now) {
        BigDecimal zero = money(BigDecimal.ZERO);
        BigDecimal hours = CancellationPolicyCalculator.hoursBefore(booking.getStartTime(), now);
        return switch (booking.getStatus()) {
            case PENDING_PAYMENT -> new CancellationPreview(true, null, null, 100, zero, zero, hours);
            case AWAITING_APPROVAL -> new CancellationPreview(true, null, null, 100, booking.getTotalAmount(), zero,
                    hours);
            case CONFIRMED -> {
                if (!booking.getStartTime().isAfter(now)) {
                    yield refused(STARTED, hours);
                }
                CancellationPolicy policy = booking.getListing().getCancellationPolicy();
                Result result = CancellationPolicyCalculator.preview(policy, booking.getStartTime(), now,
                        booking.getBaseAmount());
                yield new CancellationPreview(true, null, policy, result.percent(), result.refundAmount(),
                        booking.getTotalAmount().subtract(result.refundAmount()), result.hoursBeforeStart());
            }
            case ACTIVE -> refused(STARTED, hours);
            case COMPLETED, CANCELLED, REJECTED, EXPIRED ->
                    refused("This booking is already " + statusLabel(booking.getStatus()), hours);
        };
    }

    private static CancellationPreview refused(String reason, BigDecimal hours) {
        BigDecimal zero = money(BigDecimal.ZERO);
        return new CancellationPreview(false, reason, null, 0, zero, zero, hours);
    }

    private static String statusLabel(BookingStatus status) {
        return switch (status) {
            case REJECTED -> "declined";
            default -> status.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    private static String driverRefundLine(BookingStatus from, CancellationPreview outcome) {
        if (from == BookingStatus.PENDING_PAYMENT) {
            return "You hadn't paid yet, so nothing was charged.";
        }
        String policy = outcome.policy() == null ? null : outcome.policy().name().toLowerCase(Locale.ROOT);
        if (outcome.refundAmount().signum() == 0) {
            return "No refund applies under the " + policy + " policy.";
        }
        String line = "Your refund of ₹" + outcome.refundAmount().toPlainString()
                + " will be processed to your original payment method.";
        if (outcome.nonRefundableAmount().signum() > 0) {
            line += " The remaining ₹" + outcome.nonRefundableAmount().toPlainString()
                    + " is not refundable under the " + policy + " policy.";
        }
        return line;
    }

    // ---- owner ----------------------------------------------------------------------------------------------

    /** Cancels a confirmed booking before it starts and refunds the driver in full. */
    @Transactional
    public OwnerBookingDto cancelByOwner(Long ownerId, Long bookingId, String reason) {
        if (!bookings.existsByIdAndListingOwnerId(bookingId, ownerId)) {
            throw ApiException.notFound("Booking not found");
        }
        Booking booking = locks.lock(bookingId);
        if (booking.getStatus() != BookingStatus.CONFIRMED || !booking.getStartTime().isAfter(clock.instant())) {
            throw ApiException.conflict(NOT_CANCELLABLE, booking.getStatus() == BookingStatus.CONFIRMED
                    || booking.getStatus() == BookingStatus.ACTIVE ? STARTED
                    : "Only confirmed bookings that haven't started can be cancelled (this one is "
                    + statusLabel(booking.getStatus()) + ")");
        }
        cancel(booking, BookingActor.OWNER, reason, reason);
        Payment payment = payments.findByBookingId(bookingId).orElseThrow();
        refunds.refund(booking, payment, payment.getAmount(), BookingActor.OWNER, "Booking cancelled by owner",
                RefundNotice.CANCELLATION);

        String refundLine = "Your full refund of ₹" + payment.getAmount().toPlainString()
                + " will be processed to your original payment method.";
        String driverPath = BookingPaths.driver(booking);
        notifier.notify(booking.getDriver(), NotificationType.BOOKING_CANCELLED, "Booking cancelled by the owner",
                "Your booking " + booking.getBookingCode() + " at " + booking.getListing().getTitle()
                        + " was cancelled by the owner. " + refundLine, driverPath,
                EmailTemplates.bookingCancelledByOwner(booking.getDriver(), booking, reason, refundLine,
                        link(driverPath)));
        return OwnerBookingService.toDto(booking,
                earnings.findByBookingId(bookingId).map(OwnerBookingService::net).orElse(null));
    }

    // ---- shared ---------------------------------------------------------------------------------------------

    private void cancel(Booking booking, BookingActor actor, String reason, String note) {
        BookingStatus from = booking.getStatus();
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledBy(actor);
        booking.setCancelReason(reason);
        events.record(booking, from, BookingStatus.CANCELLED, actor, note);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String link(String path) {
        return app.frontendUrl() + path;
    }
}
