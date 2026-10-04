package com.smartparking.availability.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;
import java.util.List;

/** Replace-all weekly hours. When {@code open24x7} is true the rules are ignored (and cleared). */
public record HoursRequest(@NotNull Boolean open24x7, @NotNull @Valid List<RuleRequest> rules) {

    public record RuleRequest(
            @Min(1) @Max(7) int dayOfWeek,
            @NotNull @JsonFormat(pattern = "HH:mm") LocalTime openTime,
            @NotNull @JsonFormat(pattern = "HH:mm") LocalTime closeTime) {
    }
}
