package com.smartparking.search;

import com.smartparking.common.error.ApiException;
import com.smartparking.common.model.VehicleType;
import com.smartparking.listing.Amenity;
import com.smartparking.listing.ListingType;
import com.smartparking.pricing.TimeWindow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/** A validated search request. {@code window} and {@code vehicleType} may be null. */
public record SearchCriteria(
        double lat,
        double lng,
        double radiusKm,
        TimeWindow window,
        VehicleType vehicleType,
        Set<ListingType> types,
        Set<Amenity> amenities,
        BigDecimal maxPricePerHour,
        boolean open24x7,
        SearchSort sort,
        int page,
        int size) {

    public static final double DEFAULT_RADIUS_KM = 5;
    public static final double MIN_RADIUS_KM = 0.5;
    public static final double MAX_RADIUS_KM = 25;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 50;

    public static final BigDecimal MAX_PRICE_PER_HOUR = BigDecimal.valueOf(100_000);

    private static final double MIN_LAT = 6;
    private static final double MAX_LAT = 38;
    private static final double MIN_LNG = 68;
    private static final double MAX_LNG = 98;

    /** Raw query parameters in, validated criteria out; throws INVALID_LOCATION / INVALID_TIME_RANGE / INVALID_PARAMETER. */
    public static SearchCriteria of(Double lat, Double lng, Double radiusKm, Instant start, Instant end,
            VehicleType vehicleType, Collection<ListingType> types, Collection<Amenity> amenities,
            BigDecimal maxPricePerHour, Boolean open24x7, String sort, Integer page, Integer size, Clock clock) {
        if (lat == null || lng == null || !Double.isFinite(lat) || !Double.isFinite(lng)
                || lat < MIN_LAT || lat > MAX_LAT || lng < MIN_LNG || lng > MAX_LNG) {
            throw ApiException.badRequest("INVALID_LOCATION", "Choose a location in India");
        }
        if (maxPricePerHour != null
                && (maxPricePerHour.signum() <= 0 || maxPricePerHour.compareTo(MAX_PRICE_PER_HOUR) > 0)) {
            throw ApiException.badRequest("INVALID_PARAMETER", "maxPricePerHour must be between 1 and 100000");
        }
        double radius = radiusKm == null || !Double.isFinite(radiusKm) ? DEFAULT_RADIUS_KM : radiusKm;
        radius = Math.max(MIN_RADIUS_KM, Math.min(MAX_RADIUS_KM, radius));
        Optional<TimeWindow> window = TimeWindow.optional(start, end, clock);
        return new SearchCriteria(lat, lng, radius, window.orElse(null), vehicleType,
                types == null || types.isEmpty() ? Set.of() : EnumSet.copyOf(types),
                amenities == null || amenities.isEmpty() ? Set.of() : EnumSet.copyOf(amenities),
                maxPricePerHour, Boolean.TRUE.equals(open24x7), SearchSort.parse(sort),
                page == null ? 0 : Math.max(0, page),
                size == null ? DEFAULT_SIZE : Math.max(1, Math.min(MAX_SIZE, size)));
    }
}
