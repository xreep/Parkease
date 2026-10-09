package com.smartparking.booking;

import com.smartparking.booking.dto.OwnerBookingDto;
import com.smartparking.common.config.AppProperties;
import com.smartparking.common.error.ApiException;
import com.smartparking.common.web.PageResponse;
import com.smartparking.earning.EarningStatus;
import com.smartparking.earning.OwnerEarning;
import com.smartparking.earning.OwnerEarningRepository;
import com.smartparking.email.EmailTemplates;
import com.smartparking.listing.ParkingListing;
import com.smartparking.notification.NotificationType;
import com.smartparking.notification.Notifier;
import com.smartparking.payment.RefundService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner decisions on paid booking requests, plus the system's own rejection of requests the owner left unanswered.
 *
 * <p>Every decision locks the payment row and then the booking row ({@link BookingLocks}), re-checks the status on the
 * locked row, applies the transition and the refund in one transaction, and sends emails only after it commits.
 */
@Service
@RequiredArgsConstructor
public class OwnerBookingService {

    static final String START_PASSED_REASON = "The booking start time passed before the owner responded";

    private static final List<BookingStatus> HIDDEN_FROM_OWNERS = List.of(BookingStatus.PENDING_PAYMENT,
            BookingStatus.EXPIRED);

    private final BookingRepository bookings;
    private final BookingLocks locks;
    private final BookingEvents events;
    private final RefundService refunds;
    private final OwnerEarningRepository earnings;
    private final Notifier notifier;
    private final AppProperties app;
    private final BookingProperties properties;
    private final Clock clock;

    // ---- listing --------------------------------------------------------------------------------------------

    /**
     * {@code status} filters by one status; otherwise {@code view} picks requests (default), upcoming or past.
     * Unpaid holds and lapsed holds are never visible to owners.
     */
    @Transactional(readOnly = true)
    public PageResponse<OwnerBookingDto> list(Long ownerId, BookingStatus status, String view, int page, int size) {
        Instant now = clock.instant();
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), 100);
        Page<Booking> result;
        if (status != null) {
            if (HIDDEN_FROM_OWNERS.contains(status)) {
                return new PageResponse<>(List.of(), pageNumber, pageSize, 0, 0);
            }
            Sort sort = switch (status) {
                case AWAITING_APPROVAL -> Sort.by("approvalDeadline", "id");
                case CONFIRMED, ACTIVE -> Sort.by("startTime", "id");
                default -> Sort.by(Sort.Order.desc("startTime"), Sort.Order.desc("id"));
            };
            result = bookings.findByListingOwnerIdAndStatusIn(ownerId, List.of(status),
                    PageRequest.of(pageNumber, pageSize, sort));
        } else {
            result = switch (parseView(view)) {
                case REQUESTS -> bookings.findByListingOwnerIdAndStatusIn(ownerId, List.of(BookingStatus.AWAITING_APPROVAL),
                        PageRequest.of(pageNumber, pageSize, Sort.by("approvalDeadline", "id")));
                case UPCOMING -> bookings.findUpcomingForOwner(ownerId, now,
                        PageRequest.of(pageNumber, pageSize, Sort.by("startTime", "id")));
                case PAST -> bookings.findPastForOwner(ownerId, now,
                        PageRequest.of(pageNumber, pageSize, Sort.by(Sort.Order.desc("startTime"), Sort.Order.desc("id"))));
            };
        }
        Map<Long, BigDecimal> nets = ownerNets(result.getContent().stream().map(Booking::getId).toList());
        return PageResponse.from(result.map(b -> toDto(b, nets.get(b.getId()))));
    }

    /** The next {@code limit} paid bookings (confirmed or awaiting the owner's answer) that have not started yet. */
    @Transactional(readOnly = true)
    public List<OwnerBookingDto> nextUpcoming(Long ownerId, int limit) {
        List<Booking> result = bookings.findByListingOwnerIdAndStatusInAndStartTimeAfter(ownerId,
                List.of(BookingStatus.CONFIRMED, BookingStatus.AWAITING_APPROVAL), clock.instant(),
                PageRequest.of(0, limit, Sort.by("startTime", "id")));
        Map<Long, BigDecimal> nets = ownerNets(result.stream().map(Booking::getId).toList());
        return result.stream().map(b -> toDto(b, nets.get(b.getId()))).toList();
    }

    private enum View { REQUESTS, UPCOMING, PAST }

    private static View parseView(String view) {
        if (view == null || view.isBlank()) {
            return View.REQUESTS;
        }
        try {
            return View.valueOf(view.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_VIEW", "view must be requests, upcoming or past");
        }
    }

    // ---- decisions ------------------------------------------------------------------------------------------

    /** Confirms a request that is still within its response window. */
    @Transactional
    public OwnerBookingDto approve(Long ownerId, Long bookingId) {
        requireOwned(ownerId, bookingId);
        Booking booking = locks.lock(bookingId);
        Instant now = clock.instant();
        if (booking.getStatus() != BookingStatus.AWAITING_APPROVAL
                || booking.getApprovalDeadline() == null || !booking.getApprovalDeadline().isAfter(now)) {
            throw invalidStatus("approved", booking);
        }
        if (!booking.getStartTime().isAfter(now)) {
            throw ApiException.conflict("INVALID_STATUS", "This booking's start time has passed");
        }
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setConfirmedAt(now);
        booking.setApprovalDeadline(null);
        events.record(booking, BookingStatus.AWAITING_APPROVAL, BookingStatus.CONFIRMED, BookingActor.OWNER,
                "Approved by owner");
        notifier.notify(booking.getDriver(), NotificationType.BOOKING_APPROVED, "Booking approved",
                "Your booking " + booking.getBookingCode() + " at " + booking.getListing().getTitle()
                        + " was approved.", BookingPaths.driver(booking),
                EmailTemplates.bookingApproved(booking.getDriver(), booking, driverLink(booking)));
        return toDto(booking, ownerNet(booking.getId()));
    }

    /** Declines a request and refunds the driver in full. */
    @Transactional
    public OwnerBookingDto reject(Long ownerId, Long bookingId, String reason) {
        requireOwned(ownerId, bookingId);
        Booking booking = locks.lock(bookingId);
        if (booking.getStatus() != BookingStatus.AWAITING_APPROVAL) {
            throw invalidStatus("declined", booking);
        }
        unwind(booking, BookingActor.OWNER, reason);
        notifier.notify(booking.getDriver(), NotificationType.BOOKING_DECLINED, "Booking request declined",
                "Your request " + booking.getBookingCode() + " at " + booking.getListing().getTitle()
                        + " was declined. You will be refunded in full.", BookingPaths.driver(booking),
                EmailTemplates.bookingRejected(booking.getDriver(), booking, reason, driverLink(booking)));
        return toDto(booking, ownerNet(booking.getId()));
    }

    /**
     * Rejects one request whose owner response deadline has passed (the background job's unit of work); false when it
     * was decided or is not overdue after all. Runs in its own transaction, so call it from outside one.
     */
    @Transactional
    public boolean autoRejectIfOverdue(Long bookingId) {
        Booking booking = locks.lock(bookingId);
        Instant now = clock.instant();
        Instant deadline = booking.getApprovalDeadline();
        boolean overdue = (deadline != null && !deadline.isAfter(now)) || !booking.getStartTime().isAfter(now);
        if (booking.getStatus() != BookingStatus.AWAITING_APPROVAL || !overdue) {
            return false;
        }
        unwind(booking, BookingActor.SYSTEM, autoRejectReason(booking));
        notifier.notify(booking.getDriver(), NotificationType.BOOKING_EXPIRED_REQUEST, "Booking request expired",
                "Your request " + booking.getBookingCode() + " at " + booking.getListing().getTitle()
                        + " expired without an answer. You will be refunded in full.", BookingPaths.driver(booking),
                EmailTemplates.bookingAutoRejected(booking.getDriver(), booking, driverLink(booking)));
        return true;
    }

    /**
     * A deadline at (or, for requests older than the cap, after) the start time means the start, not the response
     * window, ran out; an earlier deadline is a plain timeout even if the start has passed since.
     */
    String autoRejectReason(Booking booking) {
        if (booking.getApprovalDeadline() == null || !booking.getApprovalDeadline().isBefore(booking.getStartTime())) {
            return START_PASSED_REASON;
        }
        int hours = properties.approvalHours();
        return "The owner didn't respond within " + hours + (hours == 1 ? " hour" : " hours");
    }

    /**
     * REJECTED + full refund, in the caller's transaction and under its locks. The refund (a provider call) is the
     * last thing that touches money; the only work after it is queueing the post-commit email, which cannot fail the
     * transaction.
     */
    private void unwind(Booking booking, BookingActor actor, String reason) {
        BookingStatus from = booking.getStatus();
        booking.setStatus(BookingStatus.REJECTED);
        booking.setCancelledBy(actor);
        booking.setCancelReason(reason);
        events.record(booking, from, BookingStatus.REJECTED, actor, reason);
        refunds.refundFull(booking, actor, reason);
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private void requireOwned(Long ownerId, Long bookingId) {
        if (!bookings.existsByIdAndListingOwnerId(bookingId, ownerId)) {
            throw ApiException.notFound("Booking not found");
        }
    }

    private static ApiException invalidStatus(String action, Booking booking) {
        return ApiException.conflict("INVALID_STATUS",
                "This request can no longer be " + action + " (" + booking.getStatus() + ")");
    }

    private String driverLink(Booking booking) {
        return app.frontendUrl() + BookingPaths.driver(booking);
    }

    /** What the owner still earns from each booking (see {@link #net}); bookings without an earning are left out. */
    private Map<Long, BigDecimal> ownerNets(List<Long> bookingIds) {
        Map<Long, BigDecimal> nets = new HashMap<>();
        if (!bookingIds.isEmpty()) {
            earnings.findByBookingIdIn(bookingIds).forEach(e -> nets.put(e.getBooking().getId(), net(e)));
        }
        return nets;
    }

    private BigDecimal ownerNet(Long bookingId) {
        return earnings.findByBookingId(bookingId).map(OwnerBookingService::net).orElse(null);
    }

    /** A reversed earning (refunded away) is worth nothing, whatever its net column still says. */
    static BigDecimal net(OwnerEarning earning) {
        return earning.getStatus() == EarningStatus.REVERSED ? BigDecimal.ZERO.setScale(2) : earning.getNet();
    }

    static OwnerBookingDto toDto(Booking b, BigDecimal ownerNet) {
        ParkingListing listing = b.getListing();
        return new OwnerBookingDto(b.getId(), b.getBookingCode(), b.getStatus(), listing.getId(), listing.getTitle(),
                b.getSlot().getLabel(), b.getStartTime(), b.getEndTime(), b.getVehicleType(), b.getPlateNumber(),
                BookingMapper.firstName(b.getDriver().getName()), b.getBaseAmount(), ownerNet, b.getApprovalDeadline(),
                b.getCreatedAt());
    }
}
