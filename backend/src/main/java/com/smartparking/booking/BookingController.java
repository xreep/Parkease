package com.smartparking.booking;

import com.smartparking.booking.dto.BookingDetailDto;
import com.smartparking.booking.dto.BookingSummaryDto;
import com.smartparking.booking.dto.CheckoutDto;
import com.smartparking.booking.dto.CreateBookingRequest;
import com.smartparking.common.security.AuthUser;
import com.smartparking.common.security.Roles;
import com.smartparking.common.web.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService service;
    private final BookingQueryService queries;

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

    /** The driver's bookings: {@code view} is upcoming (default), past or all. */
    @GetMapping
    public PageResponse<BookingSummaryDto> list(@AuthenticationPrincipal AuthUser principal,
                                                @RequestParam(defaultValue = "upcoming") String view,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        Roles.requireDriver(principal);
        return queries.list(principal.id(), view, page, size);
    }

    @GetMapping("/{id}")
    public BookingDetailDto detail(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id) {
        Roles.requireDriver(principal);
        return queries.detail(principal.id(), id);
    }

    @GetMapping("/{id}/receipt")
    public ResponseEntity<byte[]> receipt(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id) {
        Roles.requireDriver(principal);
        BookingQueryService.Receipt receipt = queries.receipt(principal.id(), id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(receipt.fileName()).build().toString())
                .cacheControl(CacheControl.noStore())
                .body(receipt.pdf());
    }
}
