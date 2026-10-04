package com.smartparking.listing;

import com.smartparking.common.security.AuthUser;
import com.smartparking.common.web.PageResponse;
import com.smartparking.listing.dto.ListingBasicsRequest;
import com.smartparking.listing.dto.ListingDetailDto;
import com.smartparking.listing.dto.ListingSummaryDto;
import com.smartparking.listing.dto.PricingRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/owner/listings")
@RequiredArgsConstructor
public class OwnerListingController {

    private final OwnerListingService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ListingDetailDto create(@AuthenticationPrincipal AuthUser principal,
                                   @Valid @RequestBody ListingBasicsRequest request) {
        return service.create(principal.id(), request);
    }

    @GetMapping
    public PageResponse<ListingSummaryDto> list(@AuthenticationPrincipal AuthUser principal,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return service.list(principal.id(), page, size);
    }

    @GetMapping("/{id}")
    public ListingDetailDto get(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id) {
        return service.get(principal.id(), id);
    }

    @PutMapping("/{id}")
    public ListingDetailDto updateBasics(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id,
                                         @Valid @RequestBody ListingBasicsRequest request) {
        return service.updateBasics(principal.id(), id, request);
    }

    @PutMapping("/{id}/pricing")
    public ListingDetailDto updatePricing(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id,
                                          @Valid @RequestBody PricingRequest request) {
        return service.updatePricing(principal.id(), id, request);
    }

    @PostMapping("/{id}/submit")
    public ListingDetailDto submit(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id) {
        return service.submit(principal.id(), id);
    }

    @PostMapping("/{id}/pause")
    public ListingDetailDto pause(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id) {
        return service.pause(principal.id(), id);
    }

    @PostMapping("/{id}/resume")
    public ListingDetailDto resume(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id) {
        return service.resume(principal.id(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id) {
        service.delete(principal.id(), id);
    }
}
