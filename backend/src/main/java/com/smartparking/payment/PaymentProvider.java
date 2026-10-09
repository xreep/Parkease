package com.smartparking.payment;

import java.util.List;
import java.util.Map;

/** Gateway abstraction: Razorpay in production, a signature-compatible mock when no keys are configured. */
public interface PaymentProvider {

    PaymentProviderType type();

    ProviderOrder createOrder(String receipt, long amountPaise, Map<String, String> notes);

    /** True when {@code signature} is the provider's signature for this order/payment pair. */
    boolean verifyPayment(String orderId, String paymentId, String signature);

    /** The payment as the provider has it (a network call: keep it outside database transactions). */
    ProviderPayment fetchPayment(String paymentId);

    /** Captures an authorized payment for exactly {@code amountPaise}. */
    void capture(String paymentId, long amountPaise, String currency);

    /** Every payment attempt the provider has for the order; empty when it has none (or is the mock). */
    List<ProviderPayment> fetchOrderPayments(String orderId);

    ProviderRefund refund(String paymentId, long amountPaise, String reason);

    /**
     * Like {@link #refund(String, long, String)}, but retrying the same {@code idempotencyKey} must not refund twice,
     * and the refund is tagged with {@code receipt} so it can be recognised later (see {@link #fetchRefunds}).
     * Providers without such mechanisms just refund.
     */
    default ProviderRefund refund(String paymentId, long amountPaise, String reason, String idempotencyKey,
                                  String receipt) {
        return refund(paymentId, amountPaise, reason);
    }

    /** Every refund the provider has for the payment (any status). */
    List<ProviderRefund> fetchRefunds(String paymentId);

    /** True when {@code signature} matches the raw webhook body; false if no webhook secret is configured. */
    boolean verifyWebhook(String rawBody, String signature);
}
