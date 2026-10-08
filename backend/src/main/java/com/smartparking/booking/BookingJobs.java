package com.smartparking.booking;

import com.smartparking.payment.Payment;
import com.smartparking.payment.PaymentRepository;
import com.smartparking.payment.PaymentStatus;
import com.smartparking.payment.RefundService;
import java.time.Clock;
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

    static final String EXPIRED_NOTE = "Payment window expired";
    static final String HOLD_EXPIRED_REASON = "Hold expired";

    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final BookingLocks locks;
    private final BookingEvents events;
    private final OwnerBookingService ownerBookings;
    private final RefundService refunds;
    private final Clock clock;
    private final TransactionTemplate tx;

    public BookingJobs(BookingRepository bookings, PaymentRepository payments, BookingLocks locks,
                       BookingEvents events, OwnerBookingService ownerBookings, RefundService refunds, Clock clock,
                       PlatformTransactionManager txManager) {
        this.bookings = bookings;
        this.payments = payments;
        this.locks = locks;
        this.events = events;
        this.ownerBookings = ownerBookings;
        this.refunds = refunds;
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
        ids.addAll(tx.execute(s -> payments.findExpiredBookingIdsWithPaymentIn(PaymentStatus.CREATED, Limit.of(BATCH))));
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
        boolean alreadyExpired = booking.getStatus() == BookingStatus.EXPIRED
                && payment != null && payment.getStatus() == PaymentStatus.CREATED;
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
}
