package com.smartparking.payment;

import com.smartparking.booking.Booking;
import com.smartparking.booking.BookingActor;
import com.smartparking.booking.BookingEvents;
import com.smartparking.booking.BookingLocks;
import com.smartparking.common.config.AppProperties;
import com.smartparking.common.error.ApiException;
import com.smartparking.earning.EarningStatus;
import com.smartparking.earning.OwnerEarningRepository;
import com.smartparking.email.EmailMessage;
import com.smartparking.email.EmailTemplates;
import com.smartparking.notification.NotificationType;
import com.smartparking.notification.Notifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
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
    private static final int MAX_FAILURE = 300;

    static final String PROVIDER_FAILED_NOTE = "Refund failed at the provider — retrying";
    static final String NO_MATCH_REASON = "Existing provider refund doesn't match — manual review";
    /** Error code of a refund held back because the provider holds refunds of the payment that we have no row for. */
    public static final String RECONCILIATION_REQUIRED = "REFUND_RECONCILIATION_REQUIRED";
    static final String RECONCILIATION_MESSAGE =
            "We're confirming an earlier refund for this booking. Please try again in a few minutes.";
    static final String UNVERIFIED_NOTE = "Could not check the provider's existing refunds — retrying";
    static final String EXHAUSTED_NOTE = "Refund failed after " + MAX_ATTEMPTS + " attempts — manual action needed";

    private final PaymentProvider provider;
    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final BookingLocks locks;
    private final OwnerEarningRepository earnings;
    private final BookingEvents events;
    private final Notifier notifier;
    private final AppProperties app;
    private final TransactionTemplate tx;

    public RefundService(PaymentProvider provider, RefundRepository refunds, PaymentRepository payments,
                         BookingLocks locks, OwnerEarningRepository earnings, BookingEvents events,
                         Notifier notifier, AppProperties app, PlatformTransactionManager txManager) {
        this.provider = provider;
        this.refunds = refunds;
        this.payments = payments;
        this.locks = locks;
        this.earnings = earnings;
        this.events = events;
        this.notifier = notifier;
        this.app = app;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Refunds {@code amount} of a captured payment through the provider and records it: the single place that creates
     * refund rows. Joins the caller's transaction, which must already hold the payment row lock and then the booking
     * row lock (payment first, always).
     *
     * <p>{@code 0 < amount <= remaining}, where remaining is the payment's amount less its refunds that did not fail
     * (anything else is an {@link IllegalArgumentException}). A provider failure is recorded as a
     * {@link RefundStatus#FAILED} refund (never thrown) so the money is not lost track of; callers can inspect the
     * returned status. Unless it failed, the payment becomes REFUNDED (all of it is covered) or PARTIALLY_REFUNDED and
     * the booking's refund amount is brought in line (see {@link #recompute}). The owner's earning follows
     * the refunds that are owed, failed attempts included: fully refunded payments reverse it, partial ones lower its
     * net by the refunded base (platform fee and GST are never part of a partial refund) and reverse it once nothing
     * is left. The outcome is noted in the booking's history and, when a {@code notice} is given, announced to the
     * driver (callers that announce the outcome themselves pass none).
     *
     * <p>The provider call is made inside the caller's transaction. Everything that can fail on the database side is
     * flushed before the provider is called, and only the refund row update follows it, so a rollback after money has
     * moved is as unlikely as it can be. It can still happen, and then the refund exists at the provider (a payment
     * may be refunded in parts, so the provider does not refuse the next one) while its row, and with it the
     * idempotency key, is gone. That is why the provider's own refunds of the payment are looked at first: one that no
     * row of ours accounts for is adopted when it is for the amount being refunded, and otherwise blocks the refund
     * ({@value #RECONCILIATION_REQUIRED}) rather than risk paying out twice. If the provider cannot be asked, the
     * refund is recorded as FAILED without being requested, and {@link #retry} looks again later.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Refund refund(Booking booking, Payment payment, BigDecimal amount, BookingActor actor, String reason,
                         RefundNotice notice) {
        payments.flush();
        BigDecimal remaining = remaining(payment);
        if (amount == null || amount.signum() <= 0 || amount.compareTo(remaining) > 0) {
            throw new IllegalArgumentException("Refund of " + amount + " is not within the " + remaining
                    + " still refundable on payment " + payment.getId());
        }
        if (payment.getStatus() != PaymentStatus.CAPTURED && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new IllegalStateException("Payment " + payment.getId() + " is " + payment.getStatus()
                    + " and cannot be refunded");
        }
        ProviderRefund adoptable = null;
        boolean unverified = false;
        try {
            adoptable = adoptableOrphan(payment, amount);
        } catch (ApiException e) {
            if (RECONCILIATION_REQUIRED.equals(e.getCode())) {
                throw e;
            }
            log.error("Could not list the provider's refunds of payment {}", payment.getId(), e);
            unverified = true;
        }
        Refund refund = new Refund();
        refund.setNotice(notice);
        refund.setPayment(payment);
        refund.setAmount(amount);
        String shortReason = abbreviate(reason);
        refund.setReason(shortReason);
        refund.setStatus(RefundStatus.PENDING);
        refunds.saveAndFlush(refund); // the row id is the provider idempotency key, so it must exist before the call
        if (adoptable != null) {
            refund.setProviderRefundId(adoptable.refundId());
            refund.setStatus(adoptable.status());
        } else if (unverified) {
            refund.setStatus(RefundStatus.FAILED);
            refund.setFailureReason(UNVERIFIED_NOTE);
        } else {
            issue(refund, payment.getPaymentId(), shortReason);
        }
        if (refund.getStatus() != RefundStatus.FAILED) {
            recompute(payment, booking);
        }
        adjustEarning(booking, payment);
        String money = "₹" + amount.toPlainString();
        String note = switch (refund.getStatus()) {
            case PROCESSED -> "Refund of " + money + " issued";
            case PENDING -> "Refund of " + money + " initiated";
            case FAILED -> "Refund of " + money + " could not be issued yet; it will be retried";
        };
        events.record(booking, booking.getStatus(), booking.getStatus(), actor, note);
        if (notice != RefundNotice.CANCELLATION && notice != RefundNotice.DISPUTE) { // those messages tell the driver
            notifyDriver(booking, refund);
        }
        return refund;
    }

    /**
     * Looks at the provider's live refunds of the payment for ones none of our rows accounts for (orphans: the refund
     * call went through but the transaction recording it rolled back, or someone refunded in the provider's
     * dashboard). Returns the orphan to adopt as the refund being made (same amount; one carrying our receipt
     * prefix first), nothing when there are none, and throws a 409 {@value #RECONCILIATION_REQUIRED} when there are
     * orphans but none is for {@code amount}. Provider errors propagate as {@link ApiException}s.
     */
    private ProviderRefund adoptableOrphan(Payment payment, BigDecimal amount) {
        List<Refund> ours = refunds.findByPaymentId(payment.getId()).stream()
                .filter(r -> r.getProviderPaymentId() == null)
                .toList();
        List<ProviderRefund> orphans = provider.fetchRefunds(payment.getPaymentId()).stream()
                .filter(candidate -> candidate.status() != RefundStatus.FAILED)
                .filter(candidate -> ours.stream().noneMatch(row -> candidate.refundId().equals(row.getProviderRefundId())
                        || (row.getProviderRefundId() == null && isOurs(candidate, row))))
                .toList();
        if (orphans.isEmpty()) {
            return null;
        }
        long paise = toPaise(amount);
        ProviderRefund match = orphans.stream()
                .filter(candidate -> candidate.amountPaise() != null && candidate.amountPaise() == paise)
                .min(java.util.Comparator.comparing(candidate -> !isParkeaseReceipt(candidate)))
                .orElse(null);
        if (match == null) {
            log.error("Payment {} has {} provider refund(s) with no record here, none for {} paise; holding back "
                    + "the refund for manual reconciliation", payment.getId(), orphans.size(), paise);
            throw ApiException.conflict(RECONCILIATION_REQUIRED, RECONCILIATION_MESSAGE);
        }
        log.warn("Payment {} already has refund {} ({}) at the provider with no record here; adopting it instead "
                + "of refunding again", payment.getId(), match.refundId(), match.status());
        return match;
    }

    private static boolean isParkeaseReceipt(ProviderRefund candidate) {
        return candidate.receipt() != null && candidate.receipt().startsWith("parkease-refund-");
    }

    /**
     * What can still be given back on the payment: its amount less every refund of its own money that is owed,
     * failed attempts included (those are retried, so the money is already spoken for). Zero when the payment holds
     * no money (not captured, or fully refunded).
     */
    @Transactional(readOnly = true)
    public BigDecimal refundableRemaining(Payment payment) {
        if (payment == null || (payment.getStatus() != PaymentStatus.CAPTURED
                && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED)) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal owed = refunds.findByPaymentId(payment.getId()).stream()
                .filter(r -> r.getProviderPaymentId() == null)
                .map(Refund::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return payment.getAmount().subtract(owed).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    /** What can still be refunded on the payment's own money: its amount less the refunds that did not fail. */
    private BigDecimal remaining(Payment payment) {
        return payment.getAmount().subtract(coveredBy(payment, null));
    }

    /** Sum of the payment's own refunds (extra payments never count) that did not fail, leaving out {@code skip}. */
    private BigDecimal coveredBy(Payment payment, Refund skip) {
        return refunds.findByPaymentId(payment.getId()).stream()
                .filter(r -> r.getProviderPaymentId() == null && r.getStatus() != RefundStatus.FAILED
                        && (skip == null || !skip.getId().equals(r.getId())))
                .map(Refund::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Follows the refunds owed on the payment (failed attempts count: the money is still owed and will be retried) in
     * the owner's earning. Everything refunded reverses it (net becomes zero). Otherwise the refund comes out of the base amount (fees
     * are never refunded in part), so {@code net = gross - refundedBase}; the commission stays, and the earning is
     * reversed once nothing is left of the net. Paid-out earnings are left alone.
     */
    private void adjustEarning(Booking booking, Payment payment) {
        // Locked (payment -> booking -> earning), so a payout marking it PAID at the same moment is not overwritten.
        earnings.findByBookingIdForUpdate(booking.getId()).ifPresent(earning -> {
            if (earning.getStatus() == EarningStatus.PAID || earning.getStatus() == EarningStatus.REVERSED) {
                return;
            }
            BigDecimal owed = refunds.findByPaymentId(payment.getId()).stream()
                    .filter(r -> r.getProviderPaymentId() == null)
                    .map(Refund::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (owed.compareTo(payment.getAmount()) >= 0) {
                earning.setNet(BigDecimal.ZERO.setScale(2)); // a reversed earning is worth nothing
                earning.setStatus(EarningStatus.REVERSED);
                return;
            }
            BigDecimal net = earning.getGross().subtract(owed.min(earning.getGross()));
            earning.setNet(net);
            if (net.signum() == 0) {
                earning.setStatus(EarningStatus.REVERSED);
            }
        });
    }

    /**
     * Asks the provider for the refund of {@code refund} (a saved row, attempt 1). A refused or unreachable gateway
     * (an {@link ApiException}) is recorded as a {@link RefundStatus#FAILED} refund so the money is not lost track of.
     * Anything else is a bug whose effect on the money is unknown, so it propagates and rolls the caller's
     * transaction back instead of being recorded as a clean FAILED refund.
     */
    private void issue(Refund refund, String providerPaymentId, String reason) {
        try {
            ProviderRefund result = provider.refund(providerPaymentId, toPaise(refund.getAmount()), reason,
                    idempotencyKey(refund), receipt(refund), notes(refund));
            refund.setProviderRefundId(result.refundId());
            refund.setStatus(result.status());
        } catch (ApiException e) {
            log.error("Provider refund {} of payment {} failed", refund.getId(), providerPaymentId, e);
            refund.setStatus(RefundStatus.FAILED);
            refund.setFailureReason(abbreviate(PaymentProviderException.describe(e), MAX_FAILURE));
        }
    }

    /** What the provider is told about a refund besides its reason: the booking, to recognise orphans by. */
    private static Map<String, String> notes(Refund refund) {
        return Map.of("bookingId", String.valueOf(refund.getPayment().getBooking().getId()));
    }

    /** Tags the provider refund with its row, so a refund found later at the provider can be recognised as ours. */
    static String receipt(Refund refund) {
        return "parkease-refund-" + refund.getId();
    }

    /**
     * Retrying a request with the same key never refunds twice at Razorpay. The first attempt uses
     * {@code parkease-refund-<row id>}; later attempts add the attempt number, because a refund that failed at the
     * provider would otherwise be answered with the same failed refund again.
     */
    static String idempotencyKey(Refund refund) {
        String key = "parkease-refund-" + refund.getId();
        return refund.getAttempts() > 1 ? key + "-" + refund.getAttempts() : key;
    }

    /**
     * Gives a booking's money back in full because the booking is being unwound (rejected by the owner or the
     * system): refunds the captured payment (which reverses the owner's earning) and notes the outcome in the
     * booking's history. Does nothing (empty result) when no payment was captured. Joins the caller's transaction,
     * which must already hold the payment row lock and then the booking row lock (payment first, always).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Refund> refundFull(Booking booking, BookingActor actor, String reason) {
        Payment payment = payments.findByBookingId(booking.getId()).orElse(null);
        if (payment == null || payment.getStatus() != PaymentStatus.CAPTURED) {
            return Optional.empty();
        }
        return Optional.of(refund(booking, payment, payment.getAmount(), actor, reason, null));
    }

    /**
     * Refunds a captured payment in full and notes the outcome in the booking's history; the booking's own status is
     * left as it is. For callers that already settled the booking's state (late payments, payments that arrive for a
     * booking that can no longer be paid). Joins the caller's transaction, which must hold the payment row lock and
     * then the booking row lock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Refund refundAndNote(Booking booking, Payment payment, BookingActor actor, String reason,
                                RefundNotice notice) {
        return refund(booking, payment, payment.getAmount(), actor, reason, notice);
    }

    /**
     * Emails the driver about a refund that carries a {@link RefundNotice}, once the provider has accepted it (a
     * refund that failed is announced when a retry gets it through). Joins the caller's transaction (lazy data).
     */
    private void notifyDriver(Booking booking, Refund refund) {
        if (refund.getNotice() == null || refund.getStatus() == RefundStatus.FAILED) {
            return;
        }
        boolean pending = refund.getStatus() == RefundStatus.PENDING;
        String path = "/driver/bookings/" + booking.getId();
        EmailMessage message = EmailTemplates.paymentRefunded(booking.getDriver(), booking,
                app.frontendUrl() + path, refund.getNotice(), pending, refund.getAmount());
        notifier.notify(booking.getDriver(), NotificationType.BOOKING_REFUNDED,
                pending ? "Refund initiated" : "Refund issued",
                "Your refund of ₹" + refund.getAmount().toPlainString() + " for booking " + booking.getBookingCode()
                        + (pending ? " is on its way." : " has been issued."),
                path, message);
    }

    /** Ids of FAILED refunds that still have attempts left, oldest first. */
    @Transactional(readOnly = true)
    public List<Long> retryableRefundIds(int limit) {
        return refunds.findRetryableIds(RefundStatus.FAILED, MAX_ATTEMPTS, Limit.of(limit));
    }

    /**
     * Makes one more provider attempt for a FAILED refund. Opens its own transaction (call it outside one), locking
     * the payment row and then the booking row. Skips refunds whose payment is no longer captured or whose booking
     * already got its money back. Before asking the provider for a new refund it looks at the refunds it already has
     * for the payment: a failed attempt may have reached it anyway (timeout), and such a refund is adopted instead of
     * issuing a second one. Returns true when the provider has the refund now.
     */
    public boolean retry(Long refundId) {
        return retry(refundId, false);
    }

    /**
     * Like {@link #retry(Long)}; an admin's manual retry ({@code ignoreAttemptLimit}) goes ahead even when the
     * automatic attempts are used up. The attempt still counts, and nothing else is reset.
     */
    public boolean retry(Long refundId, boolean ignoreAttemptLimit) {
        return retry(refundId, ignoreAttemptLimit, (issued, refund) -> { });
    }

    /**
     * Like {@link #retry(Long, boolean)}, and runs {@code afterOutcome} (whether the provider has the refund now, and
     * the refund as it stands) inside the same transaction, so whatever it writes (an audit row) commits or rolls back
     * together with the outcome. Not called for a refund that does not exist.
     */
    public boolean retry(Long refundId, boolean ignoreAttemptLimit, BiConsumer<Boolean, Refund> afterOutcome) {
        Boolean done = tx.execute(status -> {
            boolean issued = attemptRetry(refundId, ignoreAttemptLimit);
            refunds.findById(refundId).ifPresent(refund -> afterOutcome.accept(issued, refund));
            return issued;
        });
        return Boolean.TRUE.equals(done);
    }

    private boolean attemptRetry(Long refundId, boolean ignoreAttemptLimit) {
        Long bookingId = refunds.findBookingIdById(refundId).orElse(null);
        if (bookingId == null) {
            return false;
        }
        Booking booking = locks.lock(bookingId); // payment row, then booking row
        Payment payment = payments.findByBookingId(bookingId).orElseThrow();
        Refund refund = refunds.findById(refundId).orElseThrow();
        boolean extraPayment = refund.getProviderPaymentId() != null;
        boolean refundable = payment.getStatus() == PaymentStatus.CAPTURED
                || payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED;
        if (refund.getStatus() != RefundStatus.FAILED || (!ignoreAttemptLimit && refund.getAttempts() >= MAX_ATTEMPTS)
                || (!extraPayment && (!refundable
                || booking.getRefundAmount().compareTo(payment.getAmount()) >= 0
                // the payment's other refunds (partial ones included) leave no room for this one any more
                || coveredBy(payment, refund).add(refund.getAmount()).compareTo(payment.getAmount()) > 0))) {
            return false;
        }
        String providerPaymentId = extraPayment ? refund.getProviderPaymentId() : payment.getPaymentId();
        refund.setAttempts(refund.getAttempts() + 1);
        try {
            Existing found = lookUp(providerPaymentId, refund);
            if (found.unmatched() && found.adopted() == null) {
                // The provider has a refund for this payment that is not ours (e.g. a partial refund from its
                // dashboard). Issuing another could refund too much, adopting it could hide a shortfall.
                refund.setAttempts(MAX_ATTEMPTS);
                refund.setFailureReason(NO_MATCH_REASON);
                events.record(booking, booking.getStatus(), booking.getStatus(), BookingActor.SYSTEM,
                        "Refund of ₹" + refund.getAmount().toPlainString()
                                + " needs manual review: the provider already has a different refund for this payment");
                log.error("Refund {} of payment {}: the provider already has a refund that does not match; "
                        + "left for manual review", refundId, payment.getId());
                return false;
            }
            ProviderRefund result = found.adopted() != null ? found.adopted()
                    : provider.refund(providerPaymentId, toPaise(refund.getAmount()), refund.getReason(),
                            idempotencyKey(refund), receipt(refund), notes(refund));
            refund.setProviderRefundId(result.refundId());
            refund.setStatus(result.status());
            refund.setFailureReason(null);
        } catch (RuntimeException e) {
            // Unlike the first attempt, any provider-side failure must count here: the attempts counter is what
            // stops the job from retrying forever, and nothing else in this transaction needs to roll back.
            log.error("Retry {} of refund {} for payment {} failed", refund.getAttempts(), refundId,
                    payment.getId(), e);
            if (e instanceof ApiException api) {
                refund.setFailureReason(abbreviate(PaymentProviderException.describe(api), MAX_FAILURE));
            }
            if (refund.getAttempts() >= MAX_ATTEMPTS) {
                log.error("Giving up on refund {}: {} attempts used; it needs manual attention", refundId,
                        MAX_ATTEMPTS);
                events.record(booking, booking.getStatus(), booking.getStatus(), BookingActor.SYSTEM,
                        EXHAUSTED_NOTE);
            }
            return false;
        }
        if (!extraPayment) {
            recompute(payment, booking);
        }
        events.record(booking, booking.getStatus(), booking.getStatus(), BookingActor.SYSTEM,
                "Refund of ₹" + refund.getAmount().toPlainString() + " issued"
                        + (extraPayment ? " for an extra payment" : ""));
        notifyDriver(booking, refund);
        return true;
    }

    /** What the provider already has for the payment: a refund that is ours, and whether it has others that are not. */
    private record Existing(ProviderRefund adopted, boolean unmatched) {
    }

    /**
     * Looks at the provider's live (non-failed) refunds of the payment that no other row owns. One is adopted only if
     * it is demonstrably this row's own: same amount and either our receipt or (when the provider does not echo
     * receipts) our note. A refund of another amount or origin is never adopted.
     */
    private Existing lookUp(String providerPaymentId, Refund refund) {
        ProviderRefund adopted = null;
        boolean unmatched = false;
        // A payment may carry several refunds (partial ones); a refund that is demonstrably another row's own, whose
        // attempt reached the provider although it was recorded as failed, is that row's to adopt, not a stranger.
        List<Refund> siblings = refunds.findByPaymentId(refund.getPayment().getId()).stream()
                .filter(other -> !other.getId().equals(refund.getId()))
                .toList();
        for (ProviderRefund candidate : provider.fetchRefunds(providerPaymentId)) {
            if (candidate.status() == RefundStatus.FAILED) {
                continue;
            }
            boolean claimedElsewhere = refunds.findByProviderRefundId(candidate.refundId())
                    .map(other -> !other.getId().equals(refund.getId())).orElse(false);
            if (claimedElsewhere || isOfSibling(candidate, refund, siblings)) {
                continue;
            }
            if (adopted == null && isOurs(candidate, refund)) {
                log.warn("Refund {} already exists at the provider as {} ({}); adopting it instead of refunding again",
                        refund.getId(), candidate.refundId(), candidate.status());
                adopted = candidate;
            } else if (!isOurs(candidate, refund)) {
                unmatched = true;
            }
        }
        return new Existing(adopted, unmatched);
    }

    private static boolean isOfSibling(ProviderRefund candidate, Refund refund, List<Refund> siblings) {
        return siblings.stream().anyMatch(other -> other.getProviderPaymentId() == null
                && other.getProviderRefundId() == null && isOurs(candidate, other));
    }

    private static boolean isOurs(ProviderRefund candidate, Refund refund) {
        if (candidate.amountPaise() == null || candidate.amountPaise() != toPaise(refund.getAmount())) {
            return false;
        }
        String ours = receipt(refund);
        if (candidate.receipt() != null && !candidate.receipt().isBlank()) {
            return ours.equals(candidate.receipt());
        }
        return candidate.notes() != null && ours.equals(candidate.notes().get("parkeaseRefund"));
    }

    /**
     * Applies what the provider reports about one of its refunds (webhook {@code refund.processed} /
     * {@code refund.failed}). Opens its own transaction (call it outside one) and takes the payment row lock and then
     * the booking row lock, like every other money path. The refund is looked up again under those locks by its
     * provider id; if no refund carries that id any more (a retry replaced it, or it was never ours) the event is
     * stale and ignored. Afterwards the payment status and the booking's refunded amount are recomputed from the
     * refunds that still count, so events arriving late or out of order cannot leave the books wrong. Idempotent.
     *
     * @return false when the event was ignored (unknown or stale refund id, or a status that is not applied)
     */
    public boolean applyProviderStatus(String providerRefundId, RefundStatus newStatus, String failureReason) {
        if (providerRefundId == null || providerRefundId.isBlank()
                || (newStatus != RefundStatus.PROCESSED && newStatus != RefundStatus.FAILED)) {
            return false;
        }
        Boolean applied = tx.execute(status -> {
            Long bookingId = refunds.findBookingIdByProviderRefundId(providerRefundId).orElse(null);
            if (bookingId == null) {
                log.warn("Ignoring {} for unknown refund {}", newStatus, providerRefundId);
                return false;
            }
            Booking booking = locks.lock(bookingId); // payment row, then booking row
            Payment payment = payments.findByBookingId(bookingId).orElseThrow();
            Refund refund = refunds.findByProviderRefundId(providerRefundId).orElse(null); // re-read under the lock
            if (refund == null) {
                log.warn("Ignoring {} for refund {}: it was replaced while the event was waiting", newStatus,
                        providerRefundId);
                return false;
            }
            if (refund.getStatus() == newStatus) {
                return true; // redelivery
            }
            RefundStatus before = refund.getStatus();
            refund.setStatus(newStatus);
            if (newStatus == RefundStatus.FAILED) {
                refund.setFailureReason(abbreviate(failureReason, MAX_FAILURE));
            }
            if (refund.getProviderPaymentId() == null) {
                recompute(payment, booking);
            }
            if (before == RefundStatus.FAILED && newStatus == RefundStatus.PROCESSED) {
                notifyDriver(booking, refund); // a refund that failed has got through after all: announced once
            }
            if (newStatus == RefundStatus.FAILED) {
                events.record(booking, booking.getStatus(), booking.getStatus(), BookingActor.SYSTEM,
                        refund.getAttempts() >= MAX_ATTEMPTS ? EXHAUSTED_NOTE : PROVIDER_FAILED_NOTE);
                log.warn("Provider reported refund {} of payment {} as failed: {}", refund.getId(), payment.getId(),
                        failureReason);
            }
            return true;
        });
        return Boolean.TRUE.equals(applied);
    }

    /**
     * Brings the payment status and the booking's refunded amount in line with the refunds that count: those of the
     * payment's own money that are not FAILED. REFUNDED when they cover the payment, CAPTURED when there are none,
     * PARTIALLY_REFUNDED in between. Refunds of extra payments (see {@link #refundExtraPayment}) never count.
     */
    private void recompute(Payment payment, Booking booking) {
        BigDecimal covered = refunds.findByPaymentId(payment.getId()).stream()
                .filter(r -> r.getProviderPaymentId() == null && r.getStatus() != RefundStatus.FAILED)
                .map(Refund::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (payment.getStatus() == PaymentStatus.CAPTURED || payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED
                || payment.getStatus() == PaymentStatus.REFUNDED) {
            payment.setStatus(covered.compareTo(payment.getAmount()) >= 0 ? PaymentStatus.REFUNDED
                    : covered.signum() > 0 ? PaymentStatus.PARTIALLY_REFUNDED : PaymentStatus.CAPTURED);
        }
        booking.setRefundAmount(covered.min(payment.getAmount()));
    }

    /**
     * A second provider payment was captured for an order that is already paid (the customer paid twice): gives that
     * payment back in full. It is recorded as a refund of the payment row that carries the extra provider payment id,
     * and does not touch the payment's or the booking's own state. Idempotent per extra payment id; a failed attempt
     * is left for {@link #retry}. Joins the caller's transaction (payment row lock, then booking row lock).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Refund refundExtraPayment(Booking booking, Payment payment, String extraProviderPaymentId) {
        Optional<Refund> earlier = refunds.findByProviderPaymentId(extraProviderPaymentId);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        String reason = "Extra payment for the same order";
        Refund refund = new Refund();
        refund.setPayment(payment);
        refund.setProviderPaymentId(extraProviderPaymentId);
        refund.setAmount(payment.getAmount());
        refund.setReason(reason);
        refund.setStatus(RefundStatus.PENDING);
        refunds.saveAndFlush(refund);
        issue(refund, extraProviderPaymentId, reason);
        String note = "Extra payment " + extraProviderPaymentId + " for the same order: refund of ₹"
                + payment.getAmount().toPlainString()
                + (refund.getStatus() == RefundStatus.FAILED ? " could not be issued yet; it will be retried"
                : " issued");
        events.record(booking, booking.getStatus(), booking.getStatus(), BookingActor.SYSTEM, note);
        return refund;
    }

    /** Keeps free text within what the refund row and the provider's notes accept. */
    private static String abbreviate(String reason) {
        return abbreviate(reason, MAX_REASON);
    }

    private static String abbreviate(String text, int max) {
        return text != null && text.length() > max ? text.substring(0, max) : text;
    }

    public static long toPaise(BigDecimal amount) {
        return amount.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
