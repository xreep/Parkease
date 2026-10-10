package com.smartparking.dispute;

import com.smartparking.admin.audit.AdminAuditService;
import com.smartparking.booking.Booking;
import com.smartparking.booking.BookingActor;
import com.smartparking.booking.BookingLocks;
import com.smartparking.common.config.AppProperties;
import com.smartparking.common.error.ApiException;
import com.smartparking.common.security.AuthUser;
import com.smartparking.common.web.PageResponse;
import com.smartparking.dispute.DisputeMapper.Audience;
import com.smartparking.dispute.dto.DisputeDto;
import com.smartparking.dispute.dto.DisputeSummaryDto;
import com.smartparking.dispute.dto.ResolveRequest;
import com.smartparking.earning.EarningStatus;
import com.smartparking.earning.OwnerEarning;
import com.smartparking.earning.OwnerEarningRepository;
import com.smartparking.email.EmailTemplates;
import com.smartparking.notification.NotificationType;
import com.smartparking.notification.Notifier;
import com.smartparking.payment.Payment;
import com.smartparking.payment.PaymentRepository;
import com.smartparking.payment.Refund;
import com.smartparking.payment.RefundNotice;
import com.smartparking.payment.RefundService;
import com.smartparking.payment.RefundStatus;
import com.smartparking.user.User;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The admin's side of disputes: review, and resolution with an optional refund through {@link RefundService}. */
@Service
@RequiredArgsConstructor
public class AdminDisputeService {

    private final DisputeRepository disputes;
    private final PaymentRepository payments;
    private final OwnerEarningRepository earnings;
    private final BookingLocks locks;
    private final RefundService refunds;
    private final DisputeMapper mapper;
    private final AdminAuditService audit;
    private final Notifier notifier;
    private final AppProperties app;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<DisputeSummaryDto> list(DisputeStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return PageResponse.from(disputes.findForAdmin(status, pageable).map(DisputeSummaryDto::from));
    }

    @Transactional(readOnly = true)
    public DisputeDto get(Long id) {
        return mapper.toDto(disputes.findById(id).orElseThrow(() -> ApiException.notFound("Report not found")),
                Audience.ADMIN);
    }

    @Transactional
    public DisputeDto review(AuthUser admin, Long id) {
        Dispute dispute = disputes.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("Report not found"));
        if (dispute.getStatus() != DisputeStatus.OPEN) {
            throw ApiException.conflict("INVALID_STATUS", "Only open reports can be taken under review");
        }
        dispute.setStatus(DisputeStatus.UNDER_REVIEW);
        disputes.saveAndFlush(dispute);
        audit.record(admin, "DISPUTE_UNDER_REVIEW", "DISPUTE", id, null);
        return mapper.toDto(dispute, Audience.ADMIN);
    }

    /**
     * Settles an unresolved dispute. A refund goes through {@link RefundService} under the usual payment-then-booking
     * locks (the dispute row is locked after them); the booking's own status is left as it is, and the owner's
     * earning follows the refund rules of the refund service.
     */
    @Transactional
    public DisputeDto resolve(AuthUser admin, Long id, ResolveRequest request) {
        Long bookingId = disputes.findBookingIdById(id).orElseThrow(() -> ApiException.notFound("Report not found"));
        Booking booking = locks.lock(bookingId);
        Dispute dispute = disputes.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("Report not found"));
        if (dispute.getStatus() == DisputeStatus.RESOLVED) {
            throw ApiException.conflict("INVALID_STATUS", "This report has already been resolved");
        }
        Payment payment = payments.findByBookingId(bookingId).orElse(null);
        BigDecimal remaining = refunds.refundableRemaining(payment);
        BigDecimal amount = switch (request.resolution()) {
            case REFUND_FULL -> {
                if (remaining.signum() <= 0) {
                    throw invalid("Nothing is left to refund on this booking");
                }
                yield remaining;
            }
            case REFUND_PARTIAL -> partialAmount(request.amount(), remaining);
            case NO_REFUND, WARNING -> null;
        };
        // The notice keeps the refund's own driver message away (the resolution tells the driver), except to announce
        // a refund that failed first and gets through on a retry.
        Refund refund = amount == null ? null : refunds.refund(booking, payment, amount, BookingActor.ADMIN,
                "Dispute resolved: " + request.resolution().name().toLowerCase().replace('_', ' '),
                RefundNotice.DISPUTE);
        dispute.setStatus(DisputeStatus.RESOLVED);
        dispute.setResolution(request.resolution());
        dispute.setResolutionAmount(amount);
        dispute.setAdminNotes(request.notes().trim());
        dispute.setResolvedAt(clock.instant());
        disputes.saveAndFlush(dispute);
        audit.record(admin, "DISPUTE_RESOLVED", "DISPUTE", id, request.resolution()
                + (amount == null ? "" : " ₹" + amount.toPlainString()) + ". " + dispute.getAdminNotes());

        OwnerEarning earning = earnings.findByBookingId(bookingId).orElse(null);
        String driverOutcome = driverOutcome(request.resolution(), amount, refund);
        String ownerOutcome = ownerOutcome(request.resolution(), amount, refund, earning);
        User driver = dispute.getRaisedBy();
        User owner = booking.getListing().getOwner();
        String driverPath = "/driver/bookings/" + bookingId;
        String title = "Your report was resolved";
        notifier.notify(driver, NotificationType.DISPUTE_RESOLVED, title,
                "Booking " + booking.getBookingCode() + ": " + driverOutcome, driverPath,
                EmailTemplates.disputeResolved(driver, booking, driverOutcome, app.frontendUrl() + driverPath));
        notifier.notify(owner, NotificationType.DISPUTE_RESOLVED, "A report about your parking was resolved",
                "Booking " + booking.getBookingCode() + ": " + ownerOutcome, DisputeService.OWNER_PATH,
                EmailTemplates.disputeResolved(owner, booking, ownerOutcome,
                        app.frontendUrl() + DisputeService.OWNER_PATH));
        return mapper.toDto(dispute, Audience.ADMIN);
    }

    private static BigDecimal partialAmount(BigDecimal requested, BigDecimal remaining) {
        if (requested == null || requested.signum() <= 0) {
            throw invalid("A partial refund needs an amount above zero");
        }
        BigDecimal stripped = requested.stripTrailingZeros();
        if (stripped.scale() > 2) {
            throw invalid("The amount can have at most two decimals");
        }
        BigDecimal amount = requested.setScale(2, RoundingMode.UNNECESSARY);
        if (amount.compareTo(remaining) > 0) {
            throw invalid("The amount exceeds the " + remaining.toPlainString() + " still refundable");
        }
        return amount;
    }

    /** What the driver is told: where the money goes. */
    private static String driverOutcome(DisputeResolution resolution, BigDecimal amount, Refund refund) {
        return switch (resolution) {
            case REFUND_FULL, REFUND_PARTIAL -> "A refund of ₹" + amount.toPlainString()
                    + (refund.getStatus() == RefundStatus.PROCESSED ? " has been issued to" : " will be processed to")
                    + " the original payment method.";
            case NO_REFUND -> "No refund was issued.";
            case WARNING -> "We've warned the owner and closed this report.";
        };
    }

    /** What the owner is told: what it means for their earning (as it stands after the refund). */
    private static String ownerOutcome(DisputeResolution resolution, BigDecimal amount, Refund refund,
                                       OwnerEarning earning) {
        return switch (resolution) {
            case REFUND_FULL, REFUND_PARTIAL -> "Refund of ₹" + amount.toPlainString()
                    + (refund.getStatus() == RefundStatus.PROCESSED ? " issued to the driver; "
                    : " is being processed for the driver; ") + earningLine(earning);
            case NO_REFUND -> "No refund was issued; " + earningLine(earning);
            case WARNING -> "ParkEase has issued a warning about this booking.";
        };
    }

    private static String earningLine(OwnerEarning earning) {
        if (earning == null) {
            return "there is no earning for this booking.";
        }
        if (earning.getStatus() == EarningStatus.PAID) {
            return "your earning for this booking was already paid out (₹" + earning.getNet().toPlainString() + ").";
        }
        return "your earning for this booking is now ₹" + earning.getNet().toPlainString() + ".";
    }

    private static ApiException invalid(String message) {
        return ApiException.badRequest("INVALID_RESOLUTION", message);
    }
}
