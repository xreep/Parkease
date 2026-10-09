package com.smartparking.support;

import com.smartparking.common.error.ApiException;
import com.smartparking.payment.MockPaymentProvider;
import com.smartparking.payment.ProviderRefund;
import com.smartparking.payment.RefundStatus;
import com.smartparking.payment.Signatures;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;

/**
 * The mock provider that remembers every refund it was asked for (payment, paise, idempotency key, receipt), can be told
 * to fail or to leave refunds settling, can pretend to already hold refunds, and accepts webhooks signed with
 * {@link #WEBHOOK_SECRET}. Register it as a {@code @Primary} bean in a test configuration.
 */
public class RecordingPaymentProvider extends MockPaymentProvider {

    public static final String WEBHOOK_SECRET = "whsec_recording_provider";

    /** One refund request as the provider saw it. */
    public record Call(String paymentId, long paise, String idempotencyKey, String receipt) {
    }

    public final List<Call> calls = new CopyOnWriteArrayList<>();
    public final AtomicInteger failures = new AtomicInteger();
    /** When true, accepted refunds are PENDING (settling) instead of PROCESSED. */
    public volatile boolean pendingRefunds;
    /** Refunds the provider already has (what a timed-out attempt may have left behind). */
    public volatile List<ProviderRefund> existingRefunds = List.of();

    /** The notes (besides the reason) of every refund request, in order. */
    public final List<Map<String, String>> notes = new CopyOnWriteArrayList<>();
    /** Refunds this provider accepted, as {@link #fetchRefunds} reports them when {@link #listIssuedRefunds} is set. */
    public final List<ProviderRefund> issued = new CopyOnWriteArrayList<>();
    /** When true, {@link #fetchRefunds} also lists the refunds accepted so far (what a real gateway would do). */
    public volatile boolean listIssuedRefunds;
    /** When true, {@link #fetchRefunds} fails like an unreachable gateway. */
    public volatile boolean failFetch;

    public RecordingPaymentProvider(String jwtSecret) {
        super(jwtSecret);
    }

    public void reset() {
        calls.clear();
        failures.set(0);
        pendingRefunds = false;
        existingRefunds = List.of();
        notes.clear();
        issued.clear();
        listIssuedRefunds = false;
        failFetch = false;
    }

    @Override
    public ProviderRefund refund(String paymentId, long amountPaise, String reason, String idempotencyKey,
                                 String receipt) {
        calls.add(new Call(paymentId, amountPaise, idempotencyKey, receipt));
        if (failures.get() > 0) {
            failures.decrementAndGet();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER_ERROR", "Provider is down");
        }
        ProviderRefund accepted = super.refund(paymentId, amountPaise, reason);
        ProviderRefund result = pendingRefunds ? new ProviderRefund(accepted.refundId(), RefundStatus.PENDING) : accepted;
        issued.add(new ProviderRefund(result.refundId(), result.status(), amountPaise, receipt,
                receipt == null ? Map.of() : Map.of("parkeaseRefund", receipt)));
        return result;
    }

    @Override
    public ProviderRefund refund(String paymentId, long amountPaise, String reason, String idempotencyKey,
                                 String receipt, Map<String, String> extraNotes) {
        notes.add(extraNotes);
        return refund(paymentId, amountPaise, reason, idempotencyKey, receipt);
    }

    @Override
    public List<ProviderRefund> fetchRefunds(String paymentId) {
        if (failFetch) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER_ERROR", "Provider is down");
        }
        if (!listIssuedRefunds) {
            return existingRefunds;
        }
        List<ProviderRefund> all = new java.util.ArrayList<>(existingRefunds);
        all.addAll(issued);
        return all;
    }

    @Override
    public boolean verifyWebhook(String rawBody, String signature) {
        return Signatures.matches(Signatures.hmacSha256Hex(WEBHOOK_SECRET, rawBody), signature);
    }
}
