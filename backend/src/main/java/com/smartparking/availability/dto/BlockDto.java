package com.smartparking.availability.dto;

import java.time.Instant;

public record BlockDto(Long id, Long slotId, String slotLabel, Instant startTime, Instant endTime, String reason) {
}
