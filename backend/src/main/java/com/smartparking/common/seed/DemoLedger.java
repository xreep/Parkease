package com.smartparking.common.seed;

import com.smartparking.availability.AvailabilityEvaluator;
import com.smartparking.booking.BookingActor;
import com.smartparking.booking.BookingStatus;
import com.smartparking.common.seed.DemoCatalog.DisputeText;
import com.smartparking.common.seed.DemoModel.Bk;
import com.smartparking.common.seed.DemoModel.Outcome;
import com.smartparking.common.seed.DemoModel.Person;
import com.smartparking.common.seed.DemoModel.RefundDraft;
import com.smartparking.common.util.Ist;
import com.smartparking.dispute.DisputeCategory;
import com.smartparking.dispute.DisputeResolution;
import com.smartparking.dispute.DisputeStatus;
import com.smartparking.earning.EarningStatus;
import com.smartparking.notification.NotificationType;
import com.smartparking.payment.PaymentStatus;
import com.smartparking.payment.RefundNotice;
import com.smartparking.payment.RefundStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * Turns the planned bookings into the rest of the books: payments, refunds, invoices, owner earnings and payouts,
 * reviews, disputes, notifications and admin audit rows. Every figure follows the rules the live services apply
 * (a driver cancellation refunds a share of the base amount and the owner keeps the rest; an owner, admin or system
 * cancellation refunds everything and reverses the earning; a partial refund lowers the net by the refunded amount),
 * so the seeded data is internally consistent. Pure in-memory work: rows come out as argument arrays for batch inserts.
 */
final class DemoLedger {

    static final int REVIEW_TARGET = 205;

    private static final String MONEY = "₹";

    /** Row sets in the column order of the insert statements in {@link DemoActivitySeeder}. */
    static final class Rows {
        final List<Object[]> payments = new ArrayList<>();
        final List<Object[]> bookingEvents = new ArrayList<>();
        final List<Object[]> refunds = new ArrayList<>();
        final List<Object[]> invoices = new ArrayList<>();
        final List<Object[]> earnings = new ArrayList<>();
        final List<Object[]> reviews = new ArrayList<>();
        final List<Object[]> disputes = new ArrayList<>();
        final List<Object[]> notifications = new ArrayList<>();
        final List<Object[]> adminActions = new ArrayList<>();
    }

    private record ReviewDraft(Bk booking, int rating, String comment, Instant at, String reply, Instant repliedAt,
                               Instant hiddenAt, String hiddenReason) {
    }

    private record DisputeDraft(Bk booking, DisputeCategory category, String description, DisputeStatus status,
                                String ownerResponse, Instant ownerRespondedAt, Instant reviewAt,
                                DisputeResolution resolution, BigDecimal amount, String notes, Instant resolvedAt,
                                Instant createdAt) {
    }

    private static final class EarningDraft {
        Bk booking;
        BigDecimal net;
        EarningStatus status;
        String reference;
        Instant paidAt;
        Instant eligibleAt;
        boolean held;
    }

    private record PayoutKey(long ownerId, Instant batch) {
    }

    private final Random rnd;
    private final Instant now;
    private final long adminId;
    private final Set<Long> demoUserIds;
    private final List<Bk> bookings;
    private final Rows rows = new Rows();
    private final Set<String> usedIds = new HashSet<>();
    private final List<ReviewDraft> reviews = new ArrayList<>();
    private final List<DisputeDraft> disputes = new ArrayList<>();
    private final Map<Bk, EarningDraft> earnings = new LinkedHashMap<>();

    DemoLedger(Random rnd, Instant now, long adminId, Set<Long> demoUserIds, List<Bk> bookings) {
        this.rnd = rnd;
        this.now = now;
        this.adminId = adminId;
        this.demoUserIds = demoUserIds;
        this.bookings = bookings;
    }

    /**
     * Builds all rows. {@code reviewIds} and {@code disputeIds} hand out that many fresh primary keys (so admin audit
     * rows can point at them); {@code invoiceNumbers} hands out consecutive invoice serials (the next values of the
     * database sequence). Extra admin actions of the caller (verifications, listing decisions) are added afterwards
     * with {@link #audit}.
     */
    Rows build(IntFunction<List<Long>> reviewIds, IntFunction<List<Long>> disputeIds,
               IntFunction<List<Long>> invoiceNumbers) {
        for (Bk b : bookings) {
            b.orderId = "order_mock_" + uniqueHex();
            if (b.isPaid()) {
                b.providerPaymentId = "pay_mock_" + uniqueHex();
                b.method = DemoCatalog.PAYMENT_METHODS.get(rnd.nextInt(DemoCatalog.PAYMENT_METHODS.size()));
            }
            ownRefund(b);
        }
        planReviews();
        planDisputes();
        for (Bk b : bookings) {
            b.refunds.sort(Comparator.comparing(RefundDraft::at));
            earning(b);
        }
        payouts();
        for (Bk b : bookings) {
            events(b);
            payment(b);
        }
        invoices(invoiceNumbers);
        earningRows();
        reviewRows(reviewIds.apply(reviews.size()));
        disputeRows(disputeIds.apply(disputes.size()));
        notifications();
        return rows;
    }

    // ---- refunds the booking itself caused ------------------------------------------------------------------

    private void ownRefund(Bk b) {
        if (b.refund.signum() <= 0) {
            return;
        }
        Outcome o = b.outcome;
        String reason = switch (o) {
            case CANCELLED_DRIVER, CANCELLED_DRIVER_AWAITING -> "Booking cancelled by driver";
            case CANCELLED_OWNER -> "Booking cancelled by owner";
            case CANCELLED_ADMIN -> "Booking cancelled by admin";
            default -> b.cancelReason;
        };
        RefundNotice notice = o.isRejected() ? null : RefundNotice.CANCELLATION;
        b.refunds.add(new RefundDraft(b.refund, b.decidedAt.plusSeconds(1 + rnd.nextInt(3)), reason, notice,
                o.canceller()));
    }

    // ---- reviews --------------------------------------------------------------------------------------------

    private void planReviews() {
        List<Bk> eligible = new ArrayList<>();
        List<Bk> forced = new ArrayList<>();
        for (Bk b : bookings) {
            if (b.outcome != Outcome.COMPLETED || b.wantedRating < 0
                    || b.completedAt.isAfter(now.minus(Duration.ofHours(2)))) {
                continue;
            }
            (b.wantedRating > 0 ? forced : eligible).add(b);
        }
        Collections.shuffle(eligible, rnd);
        int extra = Math.max(0, Math.min(REVIEW_TARGET - forced.size(), (int) Math.round(eligible.size() * 0.62)));
        List<Bk> chosen = new ArrayList<>(forced);
        chosen.addAll(eligible.subList(0, Math.min(extra, eligible.size())));

        // The hidden review is a one-star rant among the random ones; fall back to making one if none turned up.
        int hiddenIndex = -1;
        List<Integer> ratings = new ArrayList<>();
        for (int i = 0; i < chosen.size(); i++) {
            int rating = chosen.get(i).wantedRating > 0 ? chosen.get(i).wantedRating : drawRating();
            ratings.add(rating);
            if (hiddenIndex < 0 && rating == 1 && chosen.get(i).wantedRating == 0) {
                hiddenIndex = i;
            }
        }
        if (hiddenIndex < 0) {
            hiddenIndex = chosen.size() - 1;
            ratings.set(hiddenIndex, 1);
        }
        for (int i = 0; i < chosen.size(); i++) {
            Bk b = chosen.get(i);
            int rating = ratings.get(i);
            Instant at = b.completedAt.plusSeconds(20 * 60 + rnd.nextInt(72 * 3600));
            Instant latest = now.minusSeconds(60 + rnd.nextInt(3600));
            if (at.isAfter(latest)) {
                at = latest;
            }
            boolean hidden = i == hiddenIndex;
            String comment = hidden ? DemoCatalog.HIDDEN_REVIEW_COMMENT
                    : rating >= 4 && rnd.nextDouble() < 0.12 ? null : DemoCatalog.reviewComment(rating, rnd);
            String reply = null;
            Instant repliedAt = null;
            boolean wantsReply = b.forceReply || (!hidden && rnd.nextDouble() < (rating >= 4 ? 0.33 : 0.70));
            if (wantsReply) {
                Instant when = at.plusSeconds(3600 + rnd.nextInt(2 * 86_400));
                if (when.isAfter(now.minusSeconds(60))) {
                    when = b.forceReply ? now.minusSeconds(60) : null;
                }
                if (when != null && when.isAfter(at)) {
                    reply = DemoCatalog.ownerReply(rating, b.driver.firstName(), rnd);
                    repliedAt = when;
                }
            }
            Instant hiddenAt = null;
            if (hidden) {
                hiddenAt = at.plusSeconds(3600 + rnd.nextInt(86_400));
                if (hiddenAt.isAfter(now)) {
                    hiddenAt = now.minusSeconds(30);
                }
            }
            reviews.add(new ReviewDraft(b, rating, comment, at, reply, repliedAt, hiddenAt,
                    hidden ? DemoCatalog.HIDDEN_REVIEW_REASON : null));
        }
    }

    private int drawRating() {
        double r = rnd.nextDouble();
        return r < 0.52 ? 5 : r < 0.82 ? 4 : r < 0.91 ? 3 : r < 0.96 ? 2 : 1;
    }

    // ---- disputes -------------------------------------------------------------------------------------------

    private enum Spec { OPEN_FORCED, OPEN, OPEN_PAYMENT, UNDER_REVIEW, UNDER_REVIEW_OWNER, FULL, PARTIAL, NO_REFUND, WARNING }

    private void planDisputes() {
        List<Bk> completed = bookings.stream().filter(b -> b.outcome == Outcome.COMPLETED).toList();
        Set<Bk> used = new HashSet<>();
        for (Spec spec : Spec.values()) {
            Bk b = spec == Spec.OPEN_FORCED
                    ? completed.stream().filter(x -> x.forceDispute).findFirst().orElse(null)
                    : pickDisputed(completed, used, spec);
            if (b == null) {
                continue;
            }
            used.add(b);
            b.disputed = true;
            disputes.add(draftDispute(b, spec));
        }
    }

    private Bk pickDisputed(List<Bk> completed, Set<Bk> used, Spec spec) {
        double minHours = 8;
        double maxHours = switch (spec) {
            case OPEN, OPEN_PAYMENT, UNDER_REVIEW, UNDER_REVIEW_OWNER -> 5 * 24;
            case FULL, PARTIAL -> 3.5 * 24;
            default -> 25 * 24;
        };
        if (spec == Spec.NO_REFUND || spec == Spec.WARNING) {
            minHours = 5 * 24;
        }
        List<Bk> pool = new ArrayList<>();
        for (Bk b : completed) {
            double hours = Duration.between(b.completedAt, now).toMinutes() / 60.0;
            if (!used.contains(b) && !b.forceDispute && hours >= minHours && hours <= maxHours) {
                pool.add(b);
            }
        }
        return pool.isEmpty() ? null : pool.get(rnd.nextInt(pool.size()));
    }

    private DisputeDraft draftDispute(Bk b, Spec spec) {
        DisputeText text = DemoCatalog.DISPUTE_TEXTS.get(rnd.nextInt(DemoCatalog.DISPUTE_TEXTS.size()));
        if (spec == Spec.OPEN_PAYMENT) {
            text = DemoCatalog.DISPUTE_TEXTS.get(4);
        }
        if (spec == Spec.OPEN_FORCED) {
            text = DemoCatalog.DISPUTE_TEXTS.get(0);
        }
        long total = Duration.between(b.completedAt, now).getSeconds();
        Instant raised = b.completedAt.plusSeconds((long) (total * (0.05 + rnd.nextDouble() * 0.25)));
        long untilNow = Duration.between(raised, now).getSeconds();
        Instant review = raised.plusSeconds((long) (untilNow * (0.10 + rnd.nextDouble() * 0.30)));
        Instant resolved = review.plusSeconds((long) (Duration.between(review, now).getSeconds()
                * (0.20 + rnd.nextDouble() * 0.40)));
        boolean ownerResponded = spec == Spec.UNDER_REVIEW_OWNER || spec == Spec.FULL || spec == Spec.PARTIAL
                || spec == Spec.NO_REFUND || spec == Spec.WARNING;
        Instant responded = ownerResponded
                ? raised.plusSeconds((long) (Duration.between(raised, review).getSeconds() * (0.1 + rnd.nextDouble() * 0.6)))
                : null;
        String response = ownerResponded ? text.ownerResponse() : null;

        return switch (spec) {
            case OPEN_FORCED, OPEN, OPEN_PAYMENT -> new DisputeDraft(b, text.category(), text.description(),
                    DisputeStatus.OPEN, null, null, null, null, null, null, null, raised);
            case UNDER_REVIEW, UNDER_REVIEW_OWNER -> new DisputeDraft(b, text.category(), text.description(),
                    DisputeStatus.UNDER_REVIEW, response, responded, review, null, null, null, null, raised);
            case FULL -> resolvedDraft(b, text, raised, response, responded, review, resolved,
                    DisputeResolution.REFUND_FULL, b.quote.totalAmount(),
                    DemoCatalog.ADMIN_NOTES_REFUND_FULL.get(rnd.nextInt(DemoCatalog.ADMIN_NOTES_REFUND_FULL.size())));
            case PARTIAL -> {
                BigDecimal share = BigDecimal.valueOf(30 + rnd.nextInt(31));
                BigDecimal amount = b.quote.baseAmount().multiply(share).divide(BigDecimal.valueOf(100), 2,
                        RoundingMode.HALF_UP);
                yield resolvedDraft(b, text, raised, response, responded, review, resolved,
                        DisputeResolution.REFUND_PARTIAL, amount, DemoCatalog.ADMIN_NOTES_REFUND_PARTIAL.get(0));
            }
            case NO_REFUND -> resolvedDraft(b, text, raised, response, responded, review, resolved,
                    DisputeResolution.NO_REFUND, null, DemoCatalog.ADMIN_NOTES_NO_REFUND);
            case WARNING -> resolvedDraft(b, text, raised, response, responded, review, resolved,
                    DisputeResolution.WARNING, null, DemoCatalog.ADMIN_NOTES_WARNING);
        };
    }

    private DisputeDraft resolvedDraft(Bk b, DisputeText text, Instant raised, String response, Instant responded,
                                       Instant review, Instant resolved, DisputeResolution resolution,
                                       BigDecimal amount, String notes) {
        if (amount != null) {
            String label = resolution == DisputeResolution.REFUND_FULL ? "refund full" : "refund partial";
            b.refunds.add(new RefundDraft(amount, resolved.plusSeconds(1), "Dispute resolved: " + label,
                    RefundNotice.DISPUTE, BookingActor.ADMIN));
        }
        return new DisputeDraft(b, text.category(), text.description(), DisputeStatus.RESOLVED, response, responded,
                review, resolution, amount, notes, resolved, raised);
    }

    // ---- earnings and payouts -------------------------------------------------------------------------------

    /** The owner's earning follows the refunds exactly as RefundService.adjustEarning would leave it. */
    private void earning(Bk b) {
        if (!b.isPaid()) {
            return;
        }
        EarningDraft e = new EarningDraft();
        e.booking = b;
        BigDecimal gross = b.quote.baseAmount();
        BigDecimal owed = b.refundTotal();
        BigDecimal net = gross.subtract(owed.min(gross));
        boolean reversed = owed.compareTo(b.quote.totalAmount()) >= 0 || net.signum() == 0;
        e.net = reversed ? BigDecimal.ZERO.setScale(2) : net;
        e.held = disputes.stream().anyMatch(d -> d.booking() == b && d.status() != DisputeStatus.RESOLVED);
        if (reversed) {
            e.status = EarningStatus.REVERSED;
        } else {
            switch (b.outcome) {
                case COMPLETED -> {
                    e.status = EarningStatus.PENDING_PAYOUT;
                    e.eligibleAt = b.completedAt;
                }
                case CANCELLED_DRIVER -> {
                    e.status = EarningStatus.PENDING_PAYOUT;
                    e.eligibleAt = b.decidedAt;
                }
                default -> e.status = EarningStatus.HELD; // AWAITING, CONFIRMED, ACTIVE
            }
        }
        earnings.put(b, e);
    }

    /**
     * Admins pay owners out in weekly batches; an earning joins the first batch that is at least a day after it became
     * payable. Earnings that are too recent, and those under an unresolved dispute, stay payable but unpaid.
     */
    private void payouts() {
        LocalDate day = now.atZone(AvailabilityEvaluator.ZONE).toLocalDate().minusDays(3);
        Instant latest = day.atTime(11, 30).atZone(AvailabilityEvaluator.ZONE).toInstant();
        Map<PayoutKey, List<EarningDraft>> groups = new LinkedHashMap<>();
        for (EarningDraft e : earnings.values()) {
            if (e.status != EarningStatus.PENDING_PAYOUT || e.held) {
                continue;
            }
            Instant ready = e.eligibleAt.plus(Duration.ofDays(1));
            if (ready.isAfter(latest)) {
                continue;
            }
            long weeks = Duration.between(ready, latest).toDays() / 7;
            Instant batch = latest.minus(Duration.ofDays(7 * weeks));
            groups.computeIfAbsent(new PayoutKey(e.booking.listing.owner.id, batch), k -> new ArrayList<>()).add(e);
        }
        for (Map.Entry<PayoutKey, List<EarningDraft>> entry : groups.entrySet()) {
            Instant paidAt = entry.getKey().batch().plusSeconds(60 + rnd.nextInt(1200));
            ZonedDateTime local = paidAt.atZone(AvailabilityEvaluator.ZONE);
            String reference = null;
            while (reference == null || !usedIds.add(reference)) {
                reference = "UTR%04d%02d%02d%08d".formatted(local.getYear(), local.getMonthValue(),
                        local.getDayOfMonth(), rnd.nextInt(100_000_000));
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (EarningDraft e : entry.getValue()) {
                e.status = EarningStatus.PAID;
                e.reference = reference;
                e.paidAt = paidAt;
                sum = sum.add(e.net);
            }
            Person owner = entry.getValue().get(0).booking.listing.owner;
            int n = entry.getValue().size();
            audit(paidAt, "PAYOUT_MARKED_PAID", "OWNER", owner.id,
                    n + " earnings, " + MONEY + sum.toPlainString() + ", reference " + reference);
            tell(owner, NotificationType.PAYOUT_SENT, "Payout sent",
                    MONEY + sum.toPlainString() + " for " + n + (n == 1 ? " booking" : " bookings")
                            + " has been paid out. Reference: " + reference, "/owner/earnings", paidAt);
        }
    }

    // ---- rows -----------------------------------------------------------------------------------------------

    private void events(Bk b) {
        Instant last = b.createdAt;
        boolean needsApproval = !b.listing.autoApprove;
        event(b, null, BookingStatus.PENDING_PAYMENT, BookingActor.DRIVER, null, b.createdAt);
        if (b.outcome == Outcome.EXPIRED) {
            event(b, BookingStatus.PENDING_PAYMENT, BookingStatus.EXPIRED, BookingActor.SYSTEM,
                    "Payment window expired", b.decidedAt);
            b.updatedAt = b.decidedAt;
            return;
        }
        BookingStatus afterPayment = needsApproval ? BookingStatus.AWAITING_APPROVAL : BookingStatus.CONFIRMED;
        event(b, BookingStatus.PENDING_PAYMENT, afterPayment, BookingActor.DRIVER, "Payment received", b.paidAt);
        last = b.paidAt;
        if (b.approvedAt != null) {
            event(b, BookingStatus.AWAITING_APPROVAL, BookingStatus.CONFIRMED, BookingActor.OWNER,
                    "Approved by the owner", b.approvedAt);
            last = b.approvedAt;
        }
        switch (b.outcome) {
            case ACTIVE, COMPLETED -> {
                b.startedAt = b.start.plusSeconds(8 + rnd.nextInt(50));
                event(b, BookingStatus.CONFIRMED, BookingStatus.ACTIVE, BookingActor.SYSTEM, "Parking time started",
                        b.startedAt);
                last = b.startedAt;
                if (b.outcome == Outcome.COMPLETED) {
                    event(b, BookingStatus.ACTIVE, BookingStatus.COMPLETED, BookingActor.SYSTEM, "Parking time ended",
                            b.completedAt);
                    last = b.completedAt;
                }
                if (!b.paidAt.isAfter(b.start.minus(Duration.ofMinutes(62)))) {
                    b.reminderSentAt = b.start.minusSeconds(55 * 60L + rnd.nextInt(300));
                }
            }
            case REJECTED_OWNER, REJECTED_SYSTEM -> {
                event(b, BookingStatus.AWAITING_APPROVAL, BookingStatus.REJECTED, b.outcome.canceller(),
                        b.cancelReason, b.decidedAt);
                last = b.decidedAt;
            }
            case CANCELLED_DRIVER, CANCELLED_DRIVER_AWAITING, CANCELLED_OWNER, CANCELLED_ADMIN -> {
                String note = switch (b.outcome) {
                    case CANCELLED_ADMIN -> "Cancelled by admin: " + b.cancelReason;
                    case CANCELLED_DRIVER, CANCELLED_DRIVER_AWAITING ->
                            b.cancelReason != null ? b.cancelReason : "Cancelled by driver";
                    default -> b.cancelReason;
                };
                event(b, b.cancelledFrom, BookingStatus.CANCELLED, b.outcome.canceller(), note, b.decidedAt);
                last = b.decidedAt;
            }
            default -> { }
        }
        for (RefundDraft r : b.refunds) {
            event(b, b.status(), b.status(), r.actor(), "Refund of " + MONEY + r.amount().toPlainString() + " issued",
                    r.at());
            if (r.at().isAfter(last)) {
                last = r.at();
            }
        }
        b.updatedAt = last;
    }

    private void event(Bk b, BookingStatus from, BookingStatus to, BookingActor actor, String note, Instant at) {
        rows.bookingEvents.add(new Object[] {b.id, from == null ? null : from.name(), to.name(), actor.name(), note,
                ts(at), ts(at)});
    }

    private void payment(Bk b) {
        BigDecimal total = b.quote.totalAmount();
        Instant created = b.createdAt.plusSeconds(1);
        if (!b.isPaid()) {
            rows.payments.add(new Object[] {b.paymentId, b.id, "MOCK", b.orderId, null, total, "INR", null,
                    PaymentStatus.FAILED.name(), "Hold expired", null, ts(created), ts(b.decidedAt)});
            return;
        }
        BigDecimal refunded = b.refundTotal();
        PaymentStatus status = refunded.signum() == 0 ? PaymentStatus.CAPTURED
                : refunded.compareTo(total) >= 0 ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
        Instant updated = b.paidAt;
        for (RefundDraft r : b.refunds) {
            if (r.at().isAfter(updated)) {
                updated = r.at();
            }
            rows.refunds.add(new Object[] {b.paymentId, "rfnd_mock_" + uniqueHex(), r.amount(),
                    RefundStatus.PROCESSED.name(), r.reason(), ts(r.at()), ts(r.at()), 1,
                    r.notice() == null ? null : r.notice().name()});
        }
        rows.payments.add(new Object[] {b.paymentId, b.id, "MOCK", b.orderId, b.providerPaymentId, total, "INR",
                b.method, status.name(), null, ts(b.paidAt), ts(created), ts(updated)});
    }

    private void invoices(IntFunction<List<Long>> invoiceNumbers) {
        List<Bk> paid = bookings.stream().filter(Bk::isPaid)
                .sorted(Comparator.comparing((Bk b) -> b.paidAt).thenComparingLong(b -> b.id)).toList();
        List<Long> numbers = invoiceNumbers.apply(paid.size());
        for (int i = 0; i < paid.size(); i++) {
            Bk b = paid.get(i);
            int year = b.paidAt.atZone(AvailabilityEvaluator.ZONE).getYear();
            rows.invoices.add(new Object[] {"INV-%d-%06d".formatted(year, numbers.get(i)), b.id, b.paymentId,
                    ts(b.paidAt), ts(b.paidAt), ts(b.paidAt)});
        }
    }

    private void earningRows() {
        for (EarningDraft e : earnings.values()) {
            Bk b = e.booking;
            Instant updated = e.paidAt != null ? e.paidAt : b.updatedAt;
            rows.earnings.add(new Object[] {b.id, b.listing.owner.id, b.quote.baseAmount(), b.quote.platformFee(),
                    e.net, e.status.name(), e.reference, e.paidAt == null ? null : ts(e.paidAt), ts(b.paidAt),
                    ts(updated)});
        }
    }

    private void reviewRows(List<Long> ids) {
        for (int i = 0; i < reviews.size(); i++) {
            ReviewDraft r = reviews.get(i);
            Bk b = r.booking();
            rows.reviews.add(new Object[] {ids.get(i), b.id, b.listing.id, b.driver.id, r.rating(), r.comment(), r.reply(),
                    r.repliedAt() == null ? null : ts(r.repliedAt()), ts(r.at()), ts(latest(r.at(), r.repliedAt(),
                    r.hiddenAt())), r.hiddenAt() == null ? null : ts(r.hiddenAt()), r.hiddenReason()});
            if (r.hiddenAt() != null) {
                audit(r.hiddenAt(), "REVIEW_HIDDEN", "REVIEW", ids.get(i), "Reason: " + r.hiddenReason());
            }
        }
    }

    private void disputeRows(List<Long> ids) {
        for (int i = 0; i < disputes.size(); i++) {
            DisputeDraft d = disputes.get(i);
            Bk b = d.booking();
            Instant updated = latest(d.createdAt(), d.ownerRespondedAt(), d.reviewAt(), d.resolvedAt());
            rows.disputes.add(new Object[] {ids.get(i), b.id, b.driver.id, d.category().name(), d.description(), d.status().name(),
                    d.ownerResponse(), d.ownerRespondedAt() == null ? null : ts(d.ownerRespondedAt()), d.notes(),
                    d.resolution() == null ? null : d.resolution().name(), d.amount(),
                    d.resolvedAt() == null ? null : ts(d.resolvedAt()), ts(d.createdAt()), ts(updated)});
            if (d.reviewAt() != null) {
                audit(d.reviewAt(), "DISPUTE_UNDER_REVIEW", "DISPUTE", ids.get(i), null);
            }
            if (d.status() == DisputeStatus.RESOLVED) {
                audit(d.resolvedAt(), "DISPUTE_RESOLVED", "DISPUTE", ids.get(i), d.resolution()
                        + (d.amount() == null ? "" : " " + MONEY + d.amount().toPlainString()) + ". " + d.notes());
            }
        }
    }

    // ---- notifications --------------------------------------------------------------------------------------

    private void notifications() {
        for (Bk b : bookings) {
            if (b.outcome == Outcome.EXPIRED) {
                continue; // an unpaid hold that lapsed raises no message
            }
            Person driver = b.driver;
            Person owner = b.listing.owner;
            String title = b.listing.title;
            String code = b.code;
            String driverPath = "/driver/bookings/" + b.id;
            String ownerPath = "/owner/bookings";
            boolean needsApproval = !b.listing.autoApprove;

            if (needsApproval) {
                tell(driver, NotificationType.BOOKING_REQUESTED, "Booking request sent",
                        "Your request " + code + " at " + title + " is waiting for the owner's approval.", driverPath,
                        b.paidAt);
                tell(owner, NotificationType.OWNER_APPROVAL_NEEDED, "New booking request",
                        "Booking request " + code + " for " + title + " needs your approval.", ownerPath, b.paidAt);
            } else {
                tell(driver, NotificationType.BOOKING_CONFIRMED, "Booking confirmed",
                        "Your booking " + code + " at " + title + " is confirmed.", driverPath, b.paidAt);
                tell(owner, NotificationType.OWNER_NEW_BOOKING, "New booking",
                        "New booking " + code + " for " + title + ".", ownerPath, b.paidAt);
            }
            if (b.approvedAt != null) {
                tell(driver, NotificationType.BOOKING_APPROVED, "Booking approved",
                        "Your booking " + code + " at " + title + " was approved.", driverPath, b.approvedAt);
                tell(owner, NotificationType.OWNER_NEW_BOOKING, "New booking",
                        "New booking " + code + " for " + title + ".", ownerPath, b.approvedAt);
            }
            if (b.reminderSentAt != null) {
                tell(driver, NotificationType.BOOKING_STARTING_SOON, "Your parking starts soon",
                        "Your booking " + code + " at " + title + " starts at " + Ist.format(b.start) + ".",
                        driverPath, b.reminderSentAt);
            }
            switch (b.outcome) {
                case COMPLETED -> tell(driver, NotificationType.BOOKING_COMPLETED, "Booking completed",
                        "Thanks for parking with ParkEase. Your booking " + code + " at " + title + " is complete.",
                        driverPath + "#review", b.completedAt);
                case REJECTED_OWNER -> tell(driver, NotificationType.BOOKING_DECLINED, "Booking request declined",
                        "Your request " + code + " at " + title + " was declined. You will be refunded in full.",
                        driverPath, b.decidedAt);
                case REJECTED_SYSTEM -> tell(driver, NotificationType.BOOKING_EXPIRED_REQUEST,
                        "Booking request expired", "Your request " + code + " at " + title
                                + " expired without an answer. You will be refunded in full.", driverPath, b.decidedAt);
                case CANCELLED_DRIVER, CANCELLED_DRIVER_AWAITING -> {
                    tell(driver, NotificationType.BOOKING_CANCELLED, "Booking cancelled",
                            "Your booking " + code + " at " + title + " was cancelled. " + driverRefundLine(b),
                            driverPath, b.decidedAt);
                    tell(owner, NotificationType.OWNER_BOOKING_CANCELLED, "Booking cancelled",
                            "Booking " + code + " for " + title + " was cancelled by the driver.", ownerPath,
                            b.decidedAt);
                }
                case CANCELLED_OWNER -> tell(driver, NotificationType.BOOKING_CANCELLED,
                        "Booking cancelled by the owner", "Your booking " + code + " at " + title
                                + " was cancelled by the owner. Your full refund of " + MONEY
                                + b.quote.totalAmount().toPlainString()
                                + " will be processed to your original payment method.", driverPath, b.decidedAt);
                case CANCELLED_ADMIN -> {
                    String body = "Your booking " + code + " at " + title + " was cancelled by ParkEase. Reason: "
                            + b.cancelReason + ". Your full refund of " + MONEY + b.quote.totalAmount().toPlainString()
                            + " will be processed to your original payment method.";
                    tell(driver, NotificationType.BOOKING_CANCELLED_BY_ADMIN, "Booking cancelled by ParkEase", body,
                            driverPath, b.decidedAt);
                    tell(owner, NotificationType.BOOKING_CANCELLED_BY_ADMIN, "Booking cancelled by ParkEase",
                            "Booking " + code + " for " + title + " was cancelled by ParkEase. Reason: "
                                    + b.cancelReason, ownerPath, b.decidedAt);
                    audit(b.decidedAt, "BOOKING_CANCELLED", "BOOKING", b.id, "Reason: " + b.cancelReason
                            + ". Refunded " + MONEY + b.quote.totalAmount().toPlainString());
                }
                default -> { }
            }
        }
        for (ReviewDraft r : reviews) {
            Bk b = r.booking();
            tell(b.listing.owner, NotificationType.OWNER_NEW_REVIEW,
                    "New " + r.rating() + "★ review for " + b.listing.title,
                    r.comment() != null ? r.comment()
                            : "A driver rated " + b.listing.title + " " + r.rating() + " out of 5.",
                    "/owner/reviews", r.at());
        }
        for (DisputeDraft d : disputes) {
            Bk b = d.booking();
            String path = "/driver/bookings/" + b.id;
            tell(b.listing.owner, NotificationType.DISPUTE_OPENED, "A driver reported a problem",
                    "Booking " + b.code + " at " + b.listing.title + ": " + label(d.category())
                            + ". You can respond once.", "/owner/disputes", d.createdAt());
            if (d.ownerRespondedAt() != null) {
                tell(b.driver, NotificationType.DISPUTE_RESPONSE, "The owner responded to your report",
                        "The owner of " + b.listing.title + " responded to your report for booking " + b.code + ".",
                        path, d.ownerRespondedAt());
            }
            if (d.status() == DisputeStatus.RESOLVED) {
                String driverOutcome = switch (d.resolution()) {
                    case REFUND_FULL, REFUND_PARTIAL -> "A refund of " + MONEY + d.amount().toPlainString()
                            + " has been issued to the original payment method.";
                    case NO_REFUND -> "No refund was issued.";
                    case WARNING -> "We've warned the owner and closed this report.";
                };
                tell(b.driver, NotificationType.DISPUTE_RESOLVED, "Your report was resolved",
                        "Booking " + b.code + ": " + driverOutcome, path, d.resolvedAt());
                tell(b.listing.owner, NotificationType.DISPUTE_RESOLVED, "A report about your parking was resolved",
                        "Booking " + b.code + ": " + (d.amount() == null ? driverOutcome
                                : "Refund of " + MONEY + d.amount().toPlainString() + " issued to the driver."),
                        "/owner/disputes", d.resolvedAt());
            }
        }
    }

    private static String label(DisputeCategory c) {
        return switch (c) {
            case NO_ACCESS -> "Couldn't get in";
            case SLOT_OCCUPIED -> "Slot was occupied";
            case OVERSTAY -> "Overstay";
            case DAMAGE -> "Damage";
            case PAYMENT -> "Payment problem";
            case OTHER -> "Other";
        };
    }

    private String driverRefundLine(Bk b) {
        if (b.outcome == Outcome.CANCELLED_DRIVER_AWAITING) {
            return "Your refund of " + MONEY + b.quote.totalAmount().toPlainString()
                    + " will be processed to your original payment method.";
        }
        String policy = b.listing.policy.name().toLowerCase();
        if (b.refund.signum() == 0) {
            return "No refund applies under the " + policy + " policy.";
        }
        String line = "Your refund of " + MONEY + b.refund.toPlainString()
                + " will be processed to your original payment method.";
        BigDecimal rest = b.quote.totalAmount().subtract(b.refund);
        if (rest.signum() > 0) {
            line += " The remaining " + MONEY + rest.toPlainString() + " is not refundable under the " + policy
                    + " policy.";
        }
        return line;
    }

    /** One in-app message; older ones are mostly read, the latest mostly not. Only recent events get a message. */
    private void tell(Person user, NotificationType type, String title, String body, String link, Instant at) {
        Instant cutoff = now.minus(Duration.ofDays(demoUserIds.contains(user.id) ? 45 : 14));
        if (at == null || at.isBefore(cutoff) || at.isAfter(now)) {
            return;
        }
        boolean old = at.isBefore(now.minus(Duration.ofHours(48)));
        boolean read = rnd.nextDouble() < (old ? 0.9 : 0.25);
        Instant readAt = read ? min(at.plusSeconds(300 + rnd.nextInt(12 * 3600)), now.minusSeconds(10)) : null;
        rows.notifications.add(new Object[] {user.id, type.name(), title, truncate(body, 500), link,
                readAt == null ? null : ts(readAt), ts(at), ts(readAt == null ? at : readAt)});
    }

    /** An admin audit row, dated {@code at} (rows from the future are dropped). */
    void audit(Instant at, String action, String targetType, Long targetId, String details) {
        if (at == null || at.isAfter(now)) {
            return;
        }
        rows.adminActions.add(new Object[] {adminId, action, targetType, targetId, details, ts(at), ts(at)});
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private String uniqueHex() {
        String hex;
        do {
            hex = "%016x".formatted(rnd.nextLong());
        } while (!usedIds.add(hex));
        return hex;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static Instant latest(Instant first, Instant... others) {
        Instant result = first;
        for (Instant i : others) {
            if (i != null && i.isAfter(result)) {
                result = i;
            }
        }
        return result;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    static java.time.OffsetDateTime ts(Instant instant) {
        return java.time.OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }
}
