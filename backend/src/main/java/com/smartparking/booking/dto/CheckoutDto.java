package com.smartparking.booking.dto;

import com.smartparking.payment.PaymentProviderType;

/** What the frontend needs to open the payment dialog; {@code payment.amount} is in paise. */
public record CheckoutDto(BookingDetailDto booking, PaymentInfo payment) {

    public record PaymentInfo(
            PaymentProviderType provider,
            String orderId,
            long amount,
            String currency,
            String keyId,
            String name,
            String description,
            Prefill prefill) {
    }

    public record Prefill(String name, String email, String contact) {
    }
}
