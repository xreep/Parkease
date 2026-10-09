package com.smartparking.booking;

import com.smartparking.payment.Payment;
import com.smartparking.payment.PaymentProviderType;
import com.smartparking.payment.PaymentRepository;
import com.smartparking.payment.PaymentService;
import com.smartparking.payment.PaymentStatus;
import com.smartparking.payment.RefundService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
    private final Clock clock;
    private final TransactionTemplate tx;

    public BookingJobs(BookingRepository bookings, PaymentRepository payments, BookingLocks locks,
                       BookingEvents events, OwnerBookingService ownerBookings, RefundService refunds,
                       PaymentService paymentService, BookingEventRepository eventRows, Clock clock,
                       PlatformTransactionManager txManager) {
        this.bookings = bookings;
        this.payments = payments;
        this.locks = locks;
        this.events = events;
        this.ownerBookings = ownerBookings;
        this.refunds = refunds;
        this.paymentService = paymentService;
        this.eventRows = eventRows;
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
