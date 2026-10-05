package com.smartparking.search;

import com.smartparking.listing.Amenity;
import com.smartparking.listing.ListingType;
import com.smartparking.pricing.QuoteDto;
import java.math.BigDecimal;
import java.util.List;

/** {@code freeSlots} and {@code quote} are null unless the search had a time window. */
public record SearchResultDto(
        Long id,
        String title,
        ListingType listingType,
        String address,
        String cityName,
        String stateName,
        double lat,
        double lng,
        double distanceKm,
        String coverPhotoUrl,
        BigDecimal pricePerHour,
        BigDecimal pricePerDay,
        BigDecimal pricePerMonth,
        List<Amenity> amenities,
        boolean open24x7,
        BigDecimal avgRating,
        int reviewCount,
        int totalSlots,
        Integer freeSlots,
        QuoteDto quote) {
}
