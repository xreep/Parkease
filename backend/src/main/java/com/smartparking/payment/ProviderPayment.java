package com.smartparking.payment;

/**
 * A payment as the provider reports it. {@code status} is the provider's word ("captured", "authorized", "failed",
 * ...). Any field the provider cannot report is null; only the offline mock may leave the order, amount and currency
 * null (they are then not checked). {@code errorDescription} is the provider's reason for a failed payment, if any.
 */
public record ProviderPayment(String paymentId, String status, String orderId, Long amountPaise, String currency,
                              String method, String errorDescription) {

    public static final String CAPTURED = "captured";
    public static final String AUTHORIZED = "authorized";

    public ProviderPayment(String paymentId, String status, String orderId, Long amountPaise, String currency,
                           String method) {
        this(paymentId, status, orderId, amountPaise, currency, method, null);
    }

    /** What the mock provider reports: captured, with nothing else known about it. */
    public static ProviderPayment assumedCaptured(String paymentId) {
        return new ProviderPayment(paymentId, CAPTURED, null, null, null, null, null);
    }
}
