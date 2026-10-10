package com.smartparking.admin.bookings;

import com.smartparking.admin.AdminFilters;
import com.smartparking.admin.audit.AdminAuditService;
import com.smartparking.booking.Booking;
import com.smartparking.booking.BookingActor;
import com.smartparking.booking.BookingEventRepository;
import com.smartparking.booking.BookingEvents;
import com.smartparking.booking.BookingLocks;
import com.smartparking.booking.BookingRepository;
import com.smartparking.booking.BookingStatus;
import com.smartparking.booking.dto.BookingDetailDto;
import com.smartparking.common.config.AppProperties;
import com.smartparking.common.error.ApiException;
import com.smartparking.common.security.AuthUser;
import com.smartparking.common.web.PageResponse;
import com.smartparking.dispute.DisputeRepository;
import com.smartparking.dispute.dto.DisputeSummaryDto;
import com.smartparking.email.EmailTemplates;
import com.smartparking.listing.ParkingListing;
import com.smartparking.notification.NotificationType;
import com.smartparking.notification.Notifier;
import com.smartparking.payment.Payment;
import com.smartparking.payment.PaymentRepository;
import com.smartparking.payment.PaymentStatus;
import com.smartparking.payment.RefundNotice;
import com.smartparking.payment.RefundRepository;
import com.smartparking.payment.RefundService;
import com.smartparking.payment.RefundStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The admin's view of every booking, and admin cancellation (full refund of what is still refundable). */
@Service
@RequiredArgsConstructor
public class AdminBookingService {

    static final Set<BookingStatus> CANCELLABLE = EnumSet.of(BookingStatus.PENDING_PAYMENT,
            BookingStatus.AWAITING_APPROVAL, BookingStatus.CONFIRMED, BookingStatus.ACTIVE);

    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final RefundRepository refundRows;
    private final BookingEventRepository eventRows;
    private final DisputeRepository disputes;
    private final BookingLocks locks;
    private final BookingEvents events;
    private final RefundService refunds;
    private final AdminAuditService audit;
    private final Notifier notifier;
    private final AppProperties app;

    @Transactional(readOnly = true)
    public PageResponse<AdminBookingSummaryDto> list(BookingStatus status, String q, Long cityId, LocalDate from,
                                                     LocalDate to, int page, int size) {
        AdminFilters.Range range = AdminFilters.istDays(from, to);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<Booking> result = bookings.adminSearch(status, cityId == null ? 0L : cityId, range.from(), range.to(),
                AdminFilters.likePattern(q), pageable);
        Map<Long, PaymentStatus> paymentStatuses = new HashMap<>();
        List<Long> ids = result.getContent().stream().map(Booking::getId).toList();
        if (!ids.isEmpty()) {
            payments.findByBookingIdIn(ids).forEach(p -> paymentStatuses.put(p.getBooking().getId(), p.getStatus()));
        }
        return PageResponse.from(result.map(b -> summary(b, paymentStatuses.get(b.getId()))));
    }

    @Transactional(readOnly = true)
    public AdminBookingDetailDto detail(Long id) {
        Booking booking = bookings.findById(id).orElseThrow(() -> ApiException.notFound("Booking not found"));
        return toDetail(booking);
    }

    /**
     * Cancels a booking that has not finished. Under the payment-then-booking locks: the booking becomes CANCELLED
     * (actor ADMIN), whatever is still refundable on its payment is refunded (which reverses the owner's earning),
     * and the driver and (unless the booking was an unpaid hold) the owner are told.
     */
    @Transactional
    public AdminBookingDetailDto cancel(AuthUser admin, Long id, String rawReason) {
        Booking booking = locks.lock(id);
        if (!CANCELLABLE.contains(booking.getStatus())) {
            throw ApiException.conflict("NOT_CANCELLABLE",
                    "This booking can no longer be cancelled (" + booking.getStatus() + ")");
        }
        String reason = rawReason.trim();
        BookingStatus from = booking.getStatus();
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledBy(BookingActor.ADMIN);
        booking.setCancelReason(reason);
        events.record(booking, from, BookingStatus.CANCELLED, BookingActor.ADMIN, "Cancelled by admin: " + reason);

        Payment payment = payments.findByBookingId(id).orElse(null);
        BigDecimal refunded = BigDecimal.ZERO;
        RefundStatus refundStatus = null;
        if (from == BookingStatus.PENDING_PAYMENT) {
            if (payment != null && payment.getStatus() == PaymentStatus.CREATED) {
                payment.setStatus(PaymentStatus.FAILED);
                payment.setFailureReason("Cancelled by admin");
            }
        } else if (payment != null) {
            BigDecimal remaining = refunds.refundableRemaining(payment);
            if (remaining.signum() > 0) {
                refundStatus = refunds.refund(booking, payment, remaining, BookingActor.ADMIN,
                        "Booking cancelled by admin", RefundNotice.CANCELLATION).getStatus();
                refunded = remaining;
            }
        }
        audit.record(admin, "BOOKING_CANCELLED", "BOOKING", id, "Reason: " + reason
                + (refunded.signum() > 0 ? ". Refund of ₹" + refunded.toPlainString() + " " + refundStatus : ""));

        ParkingListing listing = booking.getListing();
        String refundLine = refunded.signum() > 0
                ? "A refund of ₹" + refunded.toPlainString()
                + (refundStatus == RefundStatus.PROCESSED ? " has been issued to" : " will be processed to")
                + " your original payment method."
                : from == BookingStatus.PENDING_PAYMENT ? "You hadn't paid yet, so nothing was charged."
                : "No money is left to refund on this booking.";
        String driverPath = "/driver/bookings/" + id;
        notifier.notify(booking.getDriver(), NotificationType.BOOKING_CANCELLED_BY_ADMIN, "Booking cancelled by ParkEase",
                "Your booking " + booking.getBookingCode() + " at " + listing.getTitle()
                        + " was cancelled by ParkEase. Reason: " + reason + ". " + refundLine, driverPath,
                EmailTemplates.bookingCancelledByAdmin(booking.getDriver(), booking, reason, refundLine,
                        app.frontendUrl() + driverPath));
        if (from != BookingStatus.PENDING_PAYMENT) { // owners never see unpaid holds
            notifier.notify(listing.getOwner(), NotificationType.BOOKING_CANCELLED_BY_ADMIN,
                    "Booking cancelled by ParkEase",
                    "Booking " + booking.getBookingCode() + " for " + listing.getTitle()
                            + " was cancelled by ParkEase. Reason: " + reason, "/owner/bookings",
                    EmailTemplates.bookingCancelledByAdminForOwner(listing.getOwner(), booking, reason,
                            app.frontendUrl() + "/owner/bookings"));
        }
        return toDetail(booking);
    }

    private AdminBookingSummaryDto summary(Booking b, PaymentStatus paymentStatus) {
        ParkingListing l = b.getListing();
        return new AdminBookingSummaryDto(b.getId(), b.getBookingCode(), b.getStatus(), l.getTitle(),
                l.getCity().getName(), b.getDriver().getName(), b.getDriver().getEmail(), l.getOwner().getName(),
                b.getStartTime(), b.getEndTime(), b.getTotalAmount(), b.getRefundAmount(), paymentStatus,
                b.getCreatedAt());
    }

    private AdminBookingDetailDto toDetail(Booking b) {
        ParkingListing l = b.getListing();
        Payment payment = payments.findByBookingId(b.getId()).orElse(null);
        List<AdminBookingDetailDto.RefundInfo> refundInfos = payment == null ? List.of()
                : refundRows.findByPaymentId(payment.getId()).stream()
                .sorted(java.util.Comparator.comparing(com.smartparking.payment.Refund::getId))
                .map(r -> new AdminBookingDetailDto.RefundInfo(r.getId(), r.getAmount(), r.getStatus(),
                        r.getAttempts(), r.getProviderRefundId(), r.getReason(), r.getCreatedAt()))
                .toList();
        List<BookingDetailDto.Event> history = eventRows.findByBookingIdOrderByCreatedAtAscIdAsc(b.getId()).stream()
                .map(e -> new BookingDetailDto.Event(e.getFromStatus(), e.getToStatus(), e.getActor(), e.getNote(),
                        e.getCreatedAt()))
                .toList();
        List<DisputeSummaryDto> raised = disputes.findByBookingIdOrderByCreatedAtDescIdDesc(b.getId()).stream()
                .map(DisputeSummaryDto::from).toList();
        AdminBookingDetailDto.PaymentInfo paymentInfo = payment == null ? null
                : new AdminBookingDetailDto.PaymentInfo(payment.getId(), payment.getProvider(), payment.getOrderId(),
                payment.getPaymentId(), payment.getStatus(), payment.getAmount(), payment.getCapturedAt());
        return new AdminBookingDetailDto(b.getId(), b.getBookingCode(), b.getStatus(), l.getTitle(),
                l.getCity().getName(), b.getDriver().getName(), b.getDriver().getEmail(), l.getOwner().getName(),
                b.getStartTime(), b.getEndTime(), b.getTotalAmount(), b.getRefundAmount(),
                payment == null ? null : payment.getStatus(), b.getCreatedAt(), l.getId(), b.getSlot().getLabel(),
                b.getBaseAmount(), b.getPlatformFee(), b.getGstAmount(), b.getCancelReason(), b.getCancelledBy(),
                paymentInfo, refundInfos, history, raised);
    }
}
