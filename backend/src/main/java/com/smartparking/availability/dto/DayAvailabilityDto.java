package com.smartparking.availability.dto;

import com.smartparking.availability.AvailabilityLevel;
import java.time.LocalDate;

/**
 * One IST calendar day. {@code openTime}/{@code closeTime} are "HH:mm" ("24:00" for a listing open around the clock)
 * and null when the listing is closed that day; {@code bookedPercent} is 0..100.
 */
public record DayAvailabilityDto(
        LocalDate date,
        AvailabilityLevel level,
        String openTime,
        String closeTime,
        int bookedPercent) {
}
