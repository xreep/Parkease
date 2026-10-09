package com.smartparking.booking;

import com.smartparking.common.config.AppProperties;
import com.smartparking.common.util.Ist;
import com.smartparking.earning.EarningStatus;
import com.smartparking.earning.OwnerEarningRepository;
import com.smartparking.email.EmailTemplates;
import com.smartparking.notification.NotificationType;
import com.smartparking.notification.Notifier;
import com.smartparking.payment.Payment;
import com.smartparking.payment.PaymentProviderType;
import com.smartparking.payment.PaymentRepository;
import com.smartparking.payment.PaymentService;
import com.smartparking.payment.PaymentStatus;
import com.smartparking.payment.RefundService;
import com.smartparking.user.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Background housekeeping. The {@code @Scheduled} triggers only run when scheduling is enabled
 * ({@code app.jobs.enabled}, see {@link BookingJobsConfig}); the methods are public so tests call them directly.
 * Each booking is handled in its own transaction under the payment-then-booking row locks and re-checked after
 * locking, so overlapping runs (or several instances) and concurrent payments or owner decisions are harmless, and one
 * bad row never blocks the others.
 */
@Component
public class BookingJobs {

    private static final Logger log = LoggerFactory.getLogger(BookingJobs.class);

    /** Bookings handled per run; the next run picks up the rest. */
    private static final int BATCH = 200;

    /** Orders asked about per reconciliation run (each is a Razorpay API call) and how far back they may reach. */
    private static final int RECONCILE_BATCH = 100;
    private static final int RECENT_MINUTES = 60;
    private static final Duration RECENT = Duration.ofMinutes(RECENT_MINUTES);
    private static final Duration RECONCILE_WINDOW = Duration.ofHours(24);

    /** Lifecycle and reminder batch; the next run (a minute later) picks up the rest. */
    private static final int LIFECYCLE_BATCH = 500;
    private static final Duration REMINDER_LEAD = Duration.ofMinutes(60);
    private static final Duration NUDGE_LEAD = Duration.ofMinutes(30);

    private static final String OWNER_PATH = "/owner/bookings";

    static final String STARTED_NOTE = "Parking time started";
    static final String COMPLETED_NOTE = "Parking time ended";
    static final String EXPIRED_NOTE = "Payment window expired";
    static final String HOLD_EXPIRED_REASON = "Hold expired";

    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final BookingLocks locks;
    private final BookingEvents events;
    private final OwnerBookingService ownerBookings;
    private final RefundService refunds;
    private final PaymentService paymentService;
    private final BookingEventRepository eventRows;
    private final OwnerEarningRepository earnings;
    private final Notifier notifier;
    private final AppProperties app;
    private final Clock clock;
    private final TransactionTemplate tx;

    public BookingJobs(BookingRepository bookings, PaymentRepository payments, BookingLocks locks,
                       BookingEvents events, OwnerBookingService ownerBookings, RefundService refunds,
                       PaymentService paymentService, BookingEventRepository eventRows,
                       OwnerEarningRepository earnings, Notifier notifier, AppProperties app, Clock clock,
                       PlatformTransactionManager txManager) {
        this.bookings = bookings;
        this.payments = payments;
        this.locks = locks;
        this.events = events;
        this.ownerBookings = ownerBookings;
        this.refunds = refunds;
        this.paymentService = paymentService;
        this.eventRows = eventRows;
        this.earnings = earnings;
        this.notifier = notifier;
        this.app = app;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Expires unpaid holds whose window lapsed (booking EXPIRED with a history entry, open payment order FAILED). Also
     * finishes holds that the slot allocator already flipped to EXPIRED in bulk without writing history.
     */
    @Scheduled(fixedDelay = 60_000)
    public void expireHolds() {
        Instant now = clock.instant();
        List<Long> ids = new ArrayList<>(tx.execute(s -> bookings.findLapsedHoldIds(now, Limit.of(BATCH))));
        ids.addAll(tx.execute(s -> payments.findExpiredBookingIdsWithoutExpiredEvent(
                List.of(PaymentStatus.CREATED, PaymentStatus.FAILED), Limit.of(BATCH))));
        int expired = 0;
        for (Long id : ids) {
            try {
                if (Boolean.TRUE.equals(tx.execute(s -> expireOne(id, now)))) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.error("Could not expire the hold of booking {}", id, e);
            }
        }
        if (expired > 0) {
            log.info("Expired {} unpaid booking holds", expired);
        }
    }

    private boolean expireOne(Long id, Instant now) {
        Booking booking = locks.lock(id);
        Payment payment = payments.findByBookingId(id).orElse(null);
        boolean lapsedHold = booking.getStatus() == BookingStatus.PENDING_PAYMENT
                && booking.getHoldExpiresAt() != null && !booking.getHoldExpiresAt().isAfter(now);
        // Expired in bulk by the slot allocator (no history entry yet); its payment is open or already failed.
        boolean alreadyExpired = booking.getStatus() == BookingStatus.EXPIRED
                && payment != null && (payment.getStatus() == PaymentStatus.CREATED
                || payment.getStatus() == PaymentStatus.FAILED)
                && !eventRows.existsByBookingIdAndToStatus(id, BookingStatus.EXPIRED);
        if (!lapsedHold && !alreadyExpired) {
            return false;
        }
        booking.setStatus(BookingStatus.EXPIRED);
        events.record(booking, BookingStatus.PENDING_PAYMENT, BookingStatus.EXPIRED, BookingActor.SYSTEM, EXPIRED_NOTE);
        if (payment != null && payment.getStatus() == PaymentStatus.CREATED) {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason(HOLD_EXPIRED_REASON);
        }
        return true;
    }

    /** Rejects paid requests the owner did not answer in time and refunds the drivers in full. */
    @Scheduled(fixedDelay = 60_000)
    public void autoRejectOverdue() {
        List<Long> ids = tx.execute(s -> bookings.findOverdueApprovalIds(clock.instant(), Limit.of(BATCH)));
        int rejected = 0;
        for (Long id : ids) {
            try {
                if (ownerBookings.autoRejectIfOverdue(id)) {
                    rejected++;
                }
            } catch (RuntimeException e) {
                log.error("Could not auto-reject booking {}", id, e);
            }
        }
        if (rejected > 0) {
            log.info("Auto-rejected {} overdue booking requests", rejected);
        }
    }

    // ---- lifecycle ------------------------------------------------------------------------------------------

    /**
     * Moves paid bookings along with the clock: CONFIRMED bookings whose parking time began become ACTIVE, and
     * CONFIRMED/ACTIVE ones whose time is over become COMPLETED (a confirmed booking whose whole window passed while
     * the job was down goes straight to COMPLETED). Requests that were never answered are handled by
     * {@link #autoRejectOverdue()}: their deadline never lies after the start.
     */
    @Scheduled(fixedDelay = 60_000)
    public void advanceLifecycle() {
        Instant now = clock.instant();
        int completed = sweep("complete", () -> bookings.findDueToCompleteIds(now, Limit.of(LIFECYCLE_BATCH)),
                id -> completeOne(id, now));
        int started = sweep("start", () -> bookings.findDueToStartIds(now, Limit.of(LIFECYCLE_BATCH)),
                id -> startOne(id, now));
        if (started > 0 || completed > 0) {
            log.info("Lifecycle: {} bookings started, {} completed", started, completed);
        }
    }

    /**
     * One phase of a job: reads the due ids, then handles each in its own transaction. A failing query or a failing
     * booking is logged and skipped, so it never stops the other bookings or the job's other phases. Returns how many
     * bookings were actually changed.
     */
    private int sweep(String what, Supplier<List<Long>> dueIds, Predicate<Long> handle) {
        List<Long> ids;
        try {
            ids = tx.execute(s -> dueIds.get());
        } catch (RuntimeException e) {
            log.error("Could not list the bookings to {}", what, e);
            return 0;
        }
        int changed = 0;
        for (Long id : ids) {
            try {
                if (Boolean.TRUE.equals(tx.execute(s -> handle.test(id)))) {
                    changed++;
                }
            } catch (RuntimeException e) {
                log.error("Could not {} booking {}", what, id, e);
            }
        }
        return changed;
    }

    private boolean startOne(Long id, Instant now) {
        Booking booking = locks.lock(id);
        if (booking.getStatus() != BookingStatus.CONFIRMED || booking.getStartTime().isAfter(now)
                || !booking.getEndTime().isAfter(now)) {
            return false;
        }
        booking.setStatus(BookingStatus.ACTIVE);
        events.record(booking, BookingStatus.CONFIRMED, BookingStatus.ACTIVE, BookingActor.SYSTEM, STARTED_NOTE);
        return true;
    }

    private boolean completeOne(Long id, Instant now) {
        Booking booking = locks.lock(id);
        BookingStatus from = booking.getStatus();
        if ((from != BookingStatus.CONFIRMED && from != BookingStatus.ACTIVE) || booking.getEndTime().isAfter(now)) {
            return false;
        }
        booking.setStatus(BookingStatus.COMPLETED);
        booking.setCompletedAt(now);
        events.record(booking, from, BookingStatus.COMPLETED, BookingActor.SYSTEM, COMPLETED_NOTE);
        // Only a held earning becomes payable; paid-out, reversed or already released ones are left alone.
        // A held earning with nothing left (refunded away) is reversed instead.
        earnings.findByBookingId(id).ifPresent(earning -> {
            if (earning.getStatus() == EarningStatus.HELD) {
                earning.setStatus(earning.getNet().signum() > 0 ? EarningStatus.PENDING_PAYOUT : EarningStatus.REVERSED);
            }
        });
        String path = BookingPaths.driver(booking);
        notifier.notify(booking.getDriver(), NotificationType.BOOKING_COMPLETED, "Booking completed",
                "Thanks for parking with ParkEase. Your booking " + booking.getBookingCode() + " at "
                        + booking.getListing().getTitle() + " is complete.", path, null);
        return true;
    }

    // ---- reminders ------------------------------------------------------------------------------------------

    /**
     * Tells drivers their parking starts within the hour and owners that a request is about to lapse (deadline within
     * 30 minutes). Each goes out once: the sent-at column is set in the transaction that creates the notification.
     */
    @Scheduled(fixedDelay = 60_000)
    public void sendReminders() {
        Instant now = clock.instant();
        int reminded = sweep("send the start reminder of", () -> bookings.findDueForReminderIds(now,
                now.plus(REMINDER_LEAD), Limit.of(LIFECYCLE_BATCH)), id -> remindDriver(id, now));
        int nudged = sweep("send the approval reminder of", () -> bookings.findDueForApprovalNudgeIds(now,
                now.plus(NUDGE_LEAD), Limit.of(LIFECYCLE_BATCH)), id -> nudgeOwner(id, now));
        if (reminded > 0 || nudged > 0) {
            log.info("Reminders: {} drivers, {} owners", reminded, nudged);
        }
    }

    private boolean remindDriver(Long id, Instant now) {
        Booking booking = locks.lock(id);
        if (booking.getStatus() != BookingStatus.CONFIRMED || booking.getReminderSentAt() != null
                || !booking.getStartTime().isAfter(now) || booking.getStartTime().isAfter(now.plus(REMINDER_LEAD))) {
            return false;
        }
        booking.setReminderSentAt(now);
        String path = BookingPaths.driver(booking);
        notifier.notify(booking.getDriver(), NotificationType.BOOKING_STARTING_SOON, "Your parking starts soon",
                "Your booking " + booking.getBookingCode() + " at " + booking.getListing().getTitle()
                        + " starts at " + Ist.format(booking.getStartTime()) + ".", path,
                EmailTemplates.startingSoon(booking.getDriver(), booking, app.frontendUrl() + path));
        return true;
    }

    private boolean nudgeOwner(Long id, Instant now) {
        Booking booking = locks.lock(id);
        Instant deadline = booking.getApprovalDeadline();
        if (booking.getStatus() != BookingStatus.AWAITING_APPROVAL || booking.getApprovalNudgeSentAt() != null
                || deadline == null || !deadline.isAfter(now) || deadline.isAfter(now.plus(NUDGE_LEAD))) {
            return false;
        }
        booking.setApprovalNudgeSentAt(now);
        User owner = booking.getListing().getOwner();
        notifier.notify(owner, NotificationType.OWNER_APPROVAL_REMINDER, "Respond to a booking request",
                "Booking request " + booking.getBookingCode() + " for " + booking.getListing().getTitle()
                        + " expires at " + Ist.format(deadline) + ". Approve or decline it before then.",
                OWNER_PATH, EmailTemplates.approvalReminder(owner, booking, app.frontendUrl() + OWNER_PATH));
        return true;
    }

    /** Gives FAILED refunds another go (at most {@value RefundService#MAX_ATTEMPTS} provider tries in total). */
    @Scheduled(fixedDelay = 600_000)
    public void retryFailedRefunds() {
        int succeeded = 0;
        for (Long id : refunds.retryableRefundIds(BATCH)) {
            try {
                if (refunds.retry(id)) {
                    succeeded++;
                }
            } catch (RuntimeException e) {
                log.error("Could not retry refund {}", id, e);
            }
        }
        if (succeeded > 0) {
            log.info("Retried and completed {} failed refunds", succeeded);
        }
    }


    /**
     * Picks up payments that were taken at Razorpay but never confirmed here (the browser closed before the verify
     * call and the webhook was missed or is not configured). Orders from the last {@value #RECENT_MINUTES} minutes are
     * checked every five minutes: that is when a customer is waiting.
     */
    @Scheduled(fixedDelay = 300_000)
    public void reconcileRecentPayments() {
        Instant now = clock.instant();
        reconcileBand(now.minus(RECENT), now.plus(Duration.ofDays(1)), RECONCILE_BATCH);
    }

    /** Orders between one and 24 hours old (abandoned holds mostly) are checked hourly, which is plenty. */
    @Scheduled(fixedDelay = 3_600_000)
    public void reconcileOlderPayments() {
        Instant now = clock.instant();
        reconcileBand(now.minus(RECONCILE_WINDOW), now.minus(RECENT), RECONCILE_BATCH);
    }

    /**
     * Asks the provider about up to {@code cap} unconfirmed Razorpay orders created in [newerThan, olderThan),
     * newest first, and confirms what was captured. Orders whose booking is still waiting (or only just lapsed) count.
     * Errors per order are logged and skipped.
     */
    public void reconcileBand(Instant newerThan, Instant olderThan, int cap) {
        List<String> orderIds = tx.execute(s -> payments.findOrderIdsToReconcile(PaymentProviderType.RAZORPAY,
                List.of(PaymentStatus.CREATED, PaymentStatus.FAILED),
                List.of(BookingStatus.PENDING_PAYMENT, BookingStatus.EXPIRED), newerThan, olderThan, Limit.of(cap)));
        int recovered = 0;
        for (String orderId : orderIds) {
            try {
                recovered += paymentService.reconcileOrder(orderId);
            } catch (RuntimeException e) {
                log.error("Could not reconcile order {}", orderId, e);
            }
        }
        if (recovered > 0) {
            log.warn("Reconciliation recovered {} payments that had not been confirmed", recovered);
        }
    }
}
