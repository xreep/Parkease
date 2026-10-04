package com.smartparking.availability.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** A null {@code slotId} blocks the whole listing. */
public record BlockRequest(Long slotId, @NotNull Instant startTime, @NotNull Instant endTime,
                           @Size(max = 200) String reason) {
}
