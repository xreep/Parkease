package com.smartparking.booking.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record CreateBookingRequest(
        @NotNull Long listingId,
        @NotNull Long vehicleId,
        @NotNull Instant start,
        @NotNull Instant end) {
}
