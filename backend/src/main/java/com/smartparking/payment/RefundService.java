package com.smartparking.payment;

import com.smartparking.booking.Booking;
import com.smartparking.booking.BookingActor;
import com.smartparking.booking.BookingEvents;
import com.smartparking.booking.BookingLocks;
import com.smartparking.common.error.ApiException;
import com.smartparking.earning.EarningStatus;
import com.smartparking.earning.OwnerEarningRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Refunds captured payments through the provider. The single place that creates {@link Refund} rows. */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    /** Provider tries (the first one included) before a failed refund is left for a human. */
    public static final int MAX_ATTEMPTS = 5;

    private static final int MAX_REASON = 255;

    static final String PROVIDER_FAILED_NOTE = "Refund failed at the provider — retrying";
    static final String EXHAUSTED_NOTE = "Refund failed after " + MAX_ATTEMPTS + " attempts — manual action needed";

    private final PaymentProvider provider;
    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final BookingLocks locks;
    private final OwnerEarningRepository earnings;
    private final BookingEvents events;
    private final TransactionTemplate tx;

    public RefundService(PaymentProvider provider, RefundRepository refunds, PaymentRepository payments,
                         BookingLocks locks, OwnerEarningRepository earnings, BookingEvents events,
                         PlatformTransactionManager txManager) {
        this.provider = provider;
        this.refunds = refunds;
        this.payments = payments;
        this.locks = locks;
        this.earnings = earnings;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Refunds the whole payment and records it. Joins the caller's transaction. A provider failure is recorded as a
     * {@link RefundStatus#FAILED} refund (never thrown) so the money is not lost track of; callers can inspect the
     * returned status. On success the payment becomes REFUNDED and the booking's refund amount is set.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Refund refundFull(Payment payment, String reason) {
        Refund refund = new Refund();
        refund.setPayment(payment);
        refund.setAmount(payment.getAmount());
        String shortReason = abbreviate(reason);
        refund.setReason(shortReason);
        try {
            ProviderRefund result = provider.refund(payment.getPaymentId(), toPaise(payment.getAmount()), shortReason);
            refund.setProviderRefundId(result.refundId());
            refund.setStatus(result.status());
        } catch (ApiException e) {
            // Only ApiException (what the providers raise for a refused or unreachable gateway) means "the refund did
            // not happen". Anything else is a bug whose effect on the money is unknown, so it is left to propagate and
            // roll the caller's transaction back instead of being recorded as a clean FAILED refund.
            log.error("Provider refund failed for payment {}: {}", payment.getId(), e.getMessage());
            refund.setStatus(RefundStatus.FAILED);
        }
        refunds.save(refund);
        if (refund.getStatus() != RefundStatus.FAILED) {
            payment.setStatus(PaymentStatus.REFUNDED);
            payment.getBooking().setRefundAmount(payment.getAmount());
        }
        return refund;
    }

    /**
     * Gives a booking's money back in full because the booking is being unwound (rejected by the owner or the
     * system): reverses the owner's earning, refunds the captured payment and notes the outcome in the booking's
     * history. Does nothing (empty result) when no payment was captured. Joins the caller's transaction, which must
     * already hold the payment row lock and then the booking row lock (payment first, always).
     *
     * <p>The provider call is made inside that transaction. Everything that can fail on the database side is flushed
     * before the provider is called, and only the refund row insert follows it, so a rollback after money has moved
     * is as unlikely as it can be; if it ever happens the booking is still unrefunded in our books and a second
     * provider refund of the same payment is refused by the provider.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Refund> refundFull(Booking booking, BookingActor actor, String reason) {
        Payment payment = payments.findByBookingId(booking.getId()).orElse(null);
        if (payment == null || payment.getStatus() != PaymentStatus.CAPTURED) {
            return Optional.empty();
        }
        earnings.findByBookingId(booking.getId()).ifPresent(earning -> {
            if (earning.getStatus() != EarningStatus.PAID) {
                earning.setStatus(EarningStatus.REVERSED);
            }
        });
        payments.flush();

        Refund refund = refundFull(payment, reason);
        String amount = "₹" + payment.getAmount().toPlainString();
        String note = switch (refund.getStatus()) {
            case PROCESSED -> "Refund of " + amount + " issued";
            case PENDING -> "Refund of " + amount + " initiated";
            case FAILED -> "Refund of " + amount + " could not be issued yet; it will be retried";
        };
        events.record(booking, booking.getStatus(), booking.getStatus(), actor, note);
        return Optional.of(refund);
    }

    /** Ids of FAILED refunds that still have attempts left, oldest first. */
    @Transactional(readOnly = true)
    public List<Long> retryableRefundIds(int limit) {
        return refunds.findRetryableIds(RefundStatus.FAILED, MAX_ATTEMPTS, Limit.of(limit));
    }

    /**
     * Makes one more provider attempt for a FAILED refund. Opens its own transaction (call it outside one), locking
     * the payment row and then the booking row. Skips refunds whose payment is no longer captured or whose booking
     * already got its money back. Returns true when the provider accepted the refund this time.
     */
    public boolean retry(Long refundId) {
        Boolean done = tx.execute(status -> {
            Long bookingId = refunds.findBookingIdById(refundId).orElse(null);
            if (bookingId == null) {
                return false;
            }
            Booking booking = locks.lock(bookingId); // payment row, then booking row
            Payment payment = payments.findByBookingId(bookingId).orElseThrow();
            Refund refund = refunds.findById(refundId).orElseThrow();
            if (refund.getStatus() != RefundStatus.FAILED || refund.getAttempts() >= MAX_ATTEMPTS
                    || payment.getStatus() != PaymentStatus.CAPTURED
                    || booking.getRefundAmount().compareTo(payment.getAmount()) >= 0) {
                return false;
            }
            refund.setAttempts(refund.getAttempts() + 1);
            try {
                ProviderRefund result = provider.refund(payment.getPaymentId(), toPaise(refund.getAmount()),
                        refund.getReason());
                refund.setProviderRefundId(result.refundId());
                refund.setStatus(result.status());
            } catch (RuntimeException e) {
                // Unlike the first attempt, any provider-side failure must count here: the attempts counter is what
                // stops the job from retrying forever, and nothing else in this transaction needs to roll back.
                log.error("Retry {} of refund {} for payment {} failed: {}", refund.getAttempts(), refundId,
                        payment.getId(), e.getMessage());
                if (refund.getAttempts() >= MAX_ATTEMPTS) {
                    log.error("Giving up on refund {}: {} attempts used; it needs manual attention", refundId,
                            MAX_ATTEMPTS);
                    events.record(booking, booking.getStatus(), booking.getStatus(), BookingActor.SYSTEM,
                            EXHAUSTED_NOTE);
                }
                return false;
            }
            payment.setStatus(PaymentStatus.REFUNDED);
            booking.setRefundAmount(payment.getAmount());
            events.record(booking, booking.getStatus(), booking.getStatus(), BookingActor.SYSTEM,
                    "Refund of ₹" + refund.getAmount().toPlainString() + " issued");
            return true;
        });
        return Boolean.TRUE.equals(done);
    }

    /**
     * The provider reported (webhook {@code refund.failed}) that a refund it had accepted did not go through. Puts the
     * books back to "money still owed" so {@link #retry} picks it up: the refund becomes FAILED, a REFUNDED payment
     * returns to CAPTURED unless other live refunds still cover it in full, and the booking's refund amount drops by
     * this refund. Own transaction (call it outside one); payment row first, then booking row. Idempotent.
     */
    public void providerReportedFailure(Long refundId) {
        tx.executeWithoutResult(status -> {
            Long bookingId = refunds.findBookingIdById(refundId).orElse(null);
            if (bookingId == null) {
                return;
            }
            Booking booking = locks.lock(bookingId);
            Payment payment = payments.findByBookingId(bookingId).orElseThrow();
            Refund refund = refunds.findById(refundId).orElseThrow();
            if (refund.getStatus() == RefundStatus.FAILED) {
                return;
            }
            refund.setStatus(RefundStatus.FAILED);
            BigDecimal covered = refunds.findByPaymentId(payment.getId()).stream()
                    .filter(r -> !r.getId().equals(refundId))
                    .filter(r -> r.getStatus() != RefundStatus.FAILED)
                    .map(Refund::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (payment.getStatus() == PaymentStatus.REFUNDED && covered.compareTo(payment.getAmount()) < 0) {
                payment.setStatus(PaymentStatus.CAPTURED);
            }
            booking.setRefundAmount(booking.getRefundAmount().subtract(refund.getAmount()).max(BigDecimal.ZERO));
            events.record(booking, booking.getStatus(), booking.getStatus(), BookingActor.SYSTEM,
                    refund.getAttempts() >= MAX_ATTEMPTS ? EXHAUSTED_NOTE : PROVIDER_FAILED_NOTE);
            log.warn("Provider reported refund {} of payment {} as failed", refundId, payment.getId());
        });
    }

    /** Keeps free text within what the refund row and the provider's notes accept. */
    private static String abbreviate(String reason) {
        return reason != null && reason.length() > MAX_REASON ? reason.substring(0, MAX_REASON) : reason;
    }

    public static long toPaise(BigDecimal amount) {
        return amount.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
