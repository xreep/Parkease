package com.smartparking.admin;

import com.smartparking.admin.dto.AdminListingDetailDto;
import com.smartparking.admin.dto.AdminListingSummaryDto;
import com.smartparking.admin.dto.ReasonRequest;
import com.smartparking.common.web.PageResponse;
import com.smartparking.listing.ListingStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/listings")
@RequiredArgsConstructor
public class AdminListingController {

    private final AdminReviewService service;

    @GetMapping
    public PageResponse<AdminListingSummaryDto> list(
            @RequestParam(defaultValue = "PENDING_REVIEW") ListingStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listings(status, page, size);
    }

    @GetMapping("/{id}")
    public AdminListingDetailDto detail(@PathVariable Long id) {
        return service.listing(id);
    }

    @PostMapping("/{id}/approve")
    public AdminListingDetailDto approve(@PathVariable Long id) {
        return service.approveListing(id);
    }

    @PostMapping("/{id}/reject")
    public AdminListingDetailDto reject(@PathVariable Long id, @Valid @RequestBody ReasonRequest request) {
        return service.rejectListing(id, request.reason());
    }
}
