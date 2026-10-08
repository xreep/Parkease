package com.smartparking.payment;

/** An order created at the payment provider; {@code keyId} is the public checkout key (null for the mock). */
public record ProviderOrder(String orderId, long amountPaise, String currency, String keyId) {
}
