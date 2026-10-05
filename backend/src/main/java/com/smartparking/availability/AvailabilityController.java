package com.smartparking.availability;

import com.smartparking.availability.dto.BlockDto;
import com.smartparking.availability.dto.BlockRequest;
import com.smartparking.availability.dto.HoursDto;
import com.smartparking.availability.dto.HoursRequest;
import com.smartparking.common.security.AuthUser;
import jakarta.validation.Valid;
import java.util.List;
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
import org.springframework.web.bind.annotation.ResponseStatus;
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

    @GetMapping("/blocks")
    public List<BlockDto> listBlocks(@AuthenticationPrincipal AuthUser principal, @PathVariable Long listingId) {
        return service.listBlocks(principal.id(), listingId);
    }

    @PostMapping("/blocks")
    @ResponseStatus(HttpStatus.CREATED)
    public BlockDto createBlock(@AuthenticationPrincipal AuthUser principal, @PathVariable Long listingId,
                                @Valid @RequestBody BlockRequest request) {
        return service.createBlock(principal.id(), listingId, request);
    }

    @DeleteMapping("/blocks/{blockId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBlock(@AuthenticationPrincipal AuthUser principal, @PathVariable Long listingId,
                            @PathVariable Long blockId) {
        service.deleteBlock(principal.id(), listingId, blockId);
    }
}
