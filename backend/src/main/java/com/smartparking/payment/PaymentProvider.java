package com.smartparking.payment;

import java.util.Map;

/** Gateway abstraction: Razorpay in production, a signature-compatible mock when no keys are configured. */
public interface PaymentProvider {

    PaymentProviderType type();

    ProviderOrder createOrder(String receipt, long amountPaise, Map<String, String> notes);

    /** True when {@code signature} is the provider's signature for this order/payment pair. */
    boolean verifyPayment(String orderId, String paymentId, String signature);

    ProviderRefund refund(String paymentId, long amountPaise, String reason);

    /** True when {@code signature} matches the raw webhook body; false if no webhook secret is configured. */
    boolean verifyWebhook(String rawBody, String signature);
}
