package com.smartparking.availability;

import com.smartparking.availability.dto.HoursDto;
import com.smartparking.availability.dto.HoursRequest;
import com.smartparking.common.security.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/owner/listings/{listingId}")
@RequiredArgsConstructor
public class AvailabilityController {

    private final AvailabilityService service;

    @GetMapping("/hours")
    public HoursDto getHours(@AuthenticationPrincipal AuthUser principal, @PathVariable Long listingId) {
        return service.getHours(principal.id(), listingId);
    }

    @PutMapping("/hours")
    public HoursDto saveHours(@AuthenticationPrincipal AuthUser principal, @PathVariable Long listingId,
                              @Valid @RequestBody HoursRequest request) {
        return service.saveHours(principal.id(), listingId, request);
    }
}
