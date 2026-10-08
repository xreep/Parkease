package com.smartparking.booking;

import com.smartparking.booking.dto.CheckoutDto;
import com.smartparking.booking.dto.CreateBookingRequest;
import com.smartparking.common.security.AuthUser;
import com.smartparking.common.security.Roles;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CheckoutDto reserve(@AuthenticationPrincipal AuthUser principal,
                               @Valid @RequestBody CreateBookingRequest request) {
        Roles.requireDriver(principal);
        return service.reserve(principal.id(), request);
    }

    @GetMapping("/{id}/checkout")
    public CheckoutDto checkout(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id) {
        Roles.requireDriver(principal);
        return service.checkout(principal.id(), id);
    }
}
