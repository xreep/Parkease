package com.smartparking.listing.dto;

import com.smartparking.listing.ListingStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record ListingSummaryDto(
        Long id,
        String title,
        ListingStatus status,
        String cityName,
        String stateName,
        String coverPhotoUrl,
        BigDecimal pricePerHour,
        long slotCount,
        String rejectionReason,
        Instant updatedAt) {
}
