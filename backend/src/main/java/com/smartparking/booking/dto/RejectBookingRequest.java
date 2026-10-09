package com.smartparking.booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectBookingRequest(@NotBlank @Size(max = 500) String reason) {
}
