package com.smartparking.payment;

/**
 * A payment as the provider reports it. {@code status} is the provider's word ("captured", "authorized", "failed",
 * ...). Any field the provider cannot report is null and is then not checked (only the offline mock does that).
 */
public record ProviderPayment(String paymentId, String status, String orderId, Long amountPaise, String currency,
                              String method) {

    public static final String CAPTURED = "captured";
    public static final String AUTHORIZED = "authorized";

    /** What the mock provider reports: captured, with nothing else known about it. */
    public static ProviderPayment assumedCaptured(String paymentId) {
        return new ProviderPayment(paymentId, CAPTURED, null, null, null, null);
    }
}
