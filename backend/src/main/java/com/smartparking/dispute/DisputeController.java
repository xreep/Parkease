package com.smartparking.dispute;

import com.smartparking.common.security.AuthUser;
import com.smartparking.common.security.Roles;
import com.smartparking.common.web.PageResponse;
import com.smartparking.dispute.dto.CreateDisputeRequest;
import com.smartparking.dispute.dto.DisputeDto;
import com.smartparking.dispute.dto.DisputeSummaryDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The driver's side of disputes. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DisputeController {

    private final DisputeService service;

    @PostMapping("/bookings/{bookingId}/disputes")
    @ResponseStatus(HttpStatus.CREATED)
    public DisputeDto raise(@AuthenticationPrincipal AuthUser principal, @PathVariable Long bookingId,
                            @Valid @RequestBody CreateDisputeRequest request) {
        Roles.requireDriver(principal);
        return service.raise(principal.id(), bookingId, request);
    }

    @GetMapping("/disputes")
    public PageResponse<DisputeSummaryDto> list(@AuthenticationPrincipal AuthUser principal,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        Roles.requireDriver(principal);
        return service.listForDriver(principal.id(), page, size);
    }

    @GetMapping("/disputes/{id}")
    public DisputeDto get(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id) {
        Roles.requireDriver(principal);
        return service.getForDriver(principal.id(), id);
    }
}
