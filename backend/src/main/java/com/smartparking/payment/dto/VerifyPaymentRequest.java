package com.smartparking.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record VerifyPaymentRequest(
        @NotNull Long bookingId,
        @NotBlank String orderId,
        @NotBlank String paymentId,
        @NotBlank String signature) {
}
