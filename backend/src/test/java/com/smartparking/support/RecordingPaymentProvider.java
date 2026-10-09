package com.smartparking.support;

import com.smartparking.common.error.ApiException;
import com.smartparking.payment.MockPaymentProvider;
import com.smartparking.payment.ProviderRefund;
import com.smartparking.payment.RefundStatus;
import com.smartparking.payment.Signatures;
import java.util.List;
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

    public RecordingPaymentProvider(String jwtSecret) {
        super(jwtSecret);
    }

    public void reset() {
        calls.clear();
        failures.set(0);
        pendingRefunds = false;
        existingRefunds = List.of();
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
        return pendingRefunds ? new ProviderRefund(accepted.refundId(), RefundStatus.PENDING) : accepted;
    }

    @Override
    public List<ProviderRefund> fetchRefunds(String paymentId) {
        return existingRefunds;
    }

    @Override
    public boolean verifyWebhook(String rawBody, String signature) {
        return Signatures.matches(Signatures.hmacSha256Hex(WEBHOOK_SECRET, rawBody), signature);
    }
}
