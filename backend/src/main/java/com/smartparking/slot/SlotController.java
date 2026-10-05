package com.smartparking.slot;

import com.smartparking.common.security.AuthUser;
import com.smartparking.slot.dto.BulkSlotRequest;
import com.smartparking.slot.dto.SlotDto;
import com.smartparking.slot.dto.SlotRequest;
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
@RequestMapping("/api/v1/owner/listings/{listingId}/slots")
@RequiredArgsConstructor
public class SlotController {

    private final SlotService service;

    @GetMapping
    public List<SlotDto> list(@AuthenticationPrincipal AuthUser principal, @PathVariable Long listingId) {
        return service.list(principal.id(), listingId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SlotDto create(@AuthenticationPrincipal AuthUser principal, @PathVariable Long listingId,
                          @Valid @RequestBody SlotRequest request) {
        return service.create(principal.id(), listingId, request);
    }

    @PostMapping("/bulk")
    @ResponseStatus(HttpStatus.CREATED)
    public List<SlotDto> createBulk(@AuthenticationPrincipal AuthUser principal, @PathVariable Long listingId,
                                    @Valid @RequestBody BulkSlotRequest request) {
        return service.createBulk(principal.id(), listingId, request);
    }

    @PutMapping("/{slotId}")
    public SlotDto update(@AuthenticationPrincipal AuthUser principal, @PathVariable Long listingId,
                          @PathVariable Long slotId, @Valid @RequestBody SlotRequest request) {
        return service.update(principal.id(), listingId, slotId, request);
    }

    @DeleteMapping("/{slotId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser principal, @PathVariable Long listingId,
                       @PathVariable Long slotId) {
        service.delete(principal.id(), listingId, slotId);
    }
}
