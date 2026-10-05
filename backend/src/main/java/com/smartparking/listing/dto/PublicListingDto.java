package com.smartparking.listing.dto;

import com.smartparking.availability.dto.HoursRuleDto;
import com.smartparking.listing.Amenity;
import com.smartparking.listing.CancellationPolicy;
import com.smartparking.listing.ListingType;
import java.math.BigDecimal;
import java.util.List;

/** What anyone may see about an approved listing: no status, review fields, owner contact details or storage keys. */
public record PublicListingDto(
        Long id,
        String title,
        String description,
        ListingType listingType,
        String address,
        String pincode,
        double lat,
        double lng,
        String cityName,
        String citySlug,
        String stateName,
        String stateSlug,
        List<PublicPhoto> photos,
        List<Amenity> amenities,
        String rules,
        CancellationPolicy cancellationPolicy,
        boolean autoApprove,
        boolean open24x7,
        List<HoursRuleDto> hours,
        BigDecimal pricePerHour,
        BigDecimal pricePerDay,
        BigDecimal pricePerMonth,
        SlotSummary slotSummary,
        BigDecimal avgRating,
        int reviewCount,
        String ownerFirstName) {

    public record PublicPhoto(Long id, String url) {
    }

    /** Active slot counts by vehicle type and by size. */
    public record SlotSummary(int twoWheeler, int fourWheeler, int small, int medium, int large) {
    }
}
