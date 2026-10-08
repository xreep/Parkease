package com.smartparking.payment.dto;

import jakarta.validation.constraints.NotNull;

public record MockPayRequest(@NotNull Long bookingId) {
}
