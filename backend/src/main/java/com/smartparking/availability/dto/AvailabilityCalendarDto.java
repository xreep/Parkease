package com.smartparking.availability.dto;

import java.util.List;

public record AvailabilityCalendarDto(Long listingId, List<DayAvailabilityDto> days) {
}
