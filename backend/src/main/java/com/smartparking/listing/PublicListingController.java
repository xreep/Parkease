package com.smartparking.listing;

import com.smartparking.common.model.VehicleType;
import com.smartparking.listing.dto.ListingQuoteResponse;
import com.smartparking.listing.dto.PublicListingDto;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public (unauthenticated) view of APPROVED listings. */
@RestController
@RequestMapping("/api/v1/listings")
@RequiredArgsConstructor
public class PublicListingController {

    private final PublicListingService service;

    @GetMapping("/{id}")
    public PublicListingDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping("/{id}/quote")
    public ListingQuoteResponse quote(@PathVariable Long id,
                                      @RequestParam(required = false) Instant start,
                                      @RequestParam(required = false) Instant end,
                                      @RequestParam(required = false) VehicleType vehicleType) {
        return service.quote(id, start, end, vehicleType);
    }
}
