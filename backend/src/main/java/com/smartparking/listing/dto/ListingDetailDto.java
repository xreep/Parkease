package com.smartparking.listing.dto;

import com.smartparking.availability.dto.HoursRuleDto;
import com.smartparking.listing.Amenity;
import com.smartparking.listing.CancellationPolicy;
import com.smartparking.listing.ListingStatus;
import com.smartparking.listing.ListingType;
import com.smartparking.slot.dto.SlotDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ListingDetailDto(
        Long id,
        String title,
        String description,
        String address,
        String pincode,
        double lat,
        double lng,
        ListingType listingType,
        Long cityId,
        String cityName,
        String stateName,
        ListingStatus status,
        String rejectionReason,
        boolean open24x7,
        String rules,
        boolean autoApprove,
        BigDecimal pricePerHour,
        BigDecimal pricePerDay,
        BigDecimal pricePerMonth,
        CancellationPolicy cancellationPolicy,
        List<Amenity> amenities,
        List<PhotoDto> photos,
        List<SlotDto> slots,
        List<HoursRuleDto> hours,
        Instant submittedAt,
        Instant approvedAt,
        Instant updatedAt) {
}
