package com.smartparking.booking;

import com.smartparking.booking.dto.OwnerBookingDto;
import com.smartparking.booking.dto.OwnerCancelRequest;
import com.smartparking.booking.dto.RejectBookingRequest;
import com.smartparking.common.security.AuthUser;
import com.smartparking.common.web.PageResponse;
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

/** Bookings of the signed-in owner's listings (the OWNER role is enforced by the security rules for /owner/**). */
@RestController
@RequestMapping("/api/v1/owner/bookings")
@RequiredArgsConstructor
public class OwnerBookingController {

    private final OwnerBookingService service;
    private final CancellationService cancellations;

    @GetMapping
    public PageResponse<OwnerBookingDto> list(@AuthenticationPrincipal AuthUser principal,
                                              @RequestParam(required = false) BookingStatus status,
                                              @RequestParam(required = false) String view,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return service.list(principal.id(), status, view, page, size);
    }

    @PostMapping("/{id}/approve")
    public OwnerBookingDto approve(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id) {
        return service.approve(principal.id(), id);
    }

    @PostMapping("/{id}/reject")
    public OwnerBookingDto reject(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id,
                                  @Valid @RequestBody RejectBookingRequest request) {
        return service.reject(principal.id(), id, request.reason().trim());
    }

    /** Cancels a confirmed booking that has not started; the driver is refunded in full. */
    @PostMapping("/{id}/cancel")
    public OwnerBookingDto cancel(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id,
                                  @Valid @RequestBody OwnerCancelRequest request) {
        return cancellations.cancelByOwner(principal.id(), id, request.reason().trim());
    }
}
