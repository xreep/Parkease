package com.smartparking.availability.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalTime;

/** {@code dayOfWeek}: 1 = Monday .. 7 = Sunday. Times serialise as "HH:mm". */
public record HoursRuleDto(
        int dayOfWeek,
        @JsonFormat(pattern = "HH:mm") LocalTime openTime,
        @JsonFormat(pattern = "HH:mm") LocalTime closeTime) {
}
