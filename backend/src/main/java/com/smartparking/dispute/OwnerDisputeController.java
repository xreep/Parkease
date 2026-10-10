package com.smartparking.dispute;

import com.smartparking.common.security.AuthUser;
import com.smartparking.common.web.PageResponse;
import com.smartparking.dispute.dto.DisputeDto;
import com.smartparking.dispute.dto.DisputeSummaryDto;
import com.smartparking.dispute.dto.RespondRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Disputes about the signed-in owner's bookings (OWNER role enforced for /owner/**). */
@RestController
@RequestMapping("/api/v1/owner/disputes")
@RequiredArgsConstructor
public class OwnerDisputeController {

    private final DisputeService service;

    @GetMapping
    public PageResponse<DisputeSummaryDto> list(@AuthenticationPrincipal AuthUser principal,
                                                @RequestParam(required = false) DisputeStatus status,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return service.listForOwner(principal.id(), status, page, size);
    }

    @GetMapping("/{id}")
    public DisputeDto get(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id) {
        return service.getForOwner(principal.id(), id);
    }

    @PostMapping("/{id}/respond")
    public DisputeDto respond(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id,
                              @Valid @RequestBody RespondRequest request) {
        return service.respond(principal.id(), id, request.response());
    }
}
