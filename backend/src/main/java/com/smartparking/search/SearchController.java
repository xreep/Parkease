package com.smartparking.search;

import com.smartparking.common.model.VehicleType;
import com.smartparking.listing.Amenity;
import com.smartparking.listing.ListingType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public parking search. Every parameter is optional at the binding level; SearchCriteria validates. */
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService service;
    private final Clock clock;

    @GetMapping
    public SearchResponse search(
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Double radiusKm,
            @RequestParam(required = false) Instant start,
            @RequestParam(required = false) Instant end,
            @RequestParam(required = false) VehicleType vehicleType,
            @RequestParam(required = false) List<ListingType> types,
            @RequestParam(required = false) List<Amenity> amenities,
            @RequestParam(required = false) BigDecimal maxPricePerHour,
            @RequestParam(required = false) Boolean open24x7,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return service.search(SearchCriteria.of(lat, lng, radiusKm, start, end, vehicleType, types, amenities,
                maxPricePerHour, open24x7, sort, page, size, clock));
    }
}
