package com.smartparking.admin.bookings;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelBookingRequest(@NotBlank @Size(max = 300) String reason) {
}
