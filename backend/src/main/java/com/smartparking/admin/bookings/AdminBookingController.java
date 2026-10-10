package com.smartparking.admin.bookings;

import com.smartparking.booking.BookingStatus;
import com.smartparking.common.security.AuthUser;
import com.smartparking.common.web.PageResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/bookings")
@RequiredArgsConstructor
public class AdminBookingController {

    private final AdminBookingService service;

    /** {@code from} / {@code to}: IST days (inclusive) the booking starts on. */
    @GetMapping
    public PageResponse<AdminBookingSummaryDto> list(@RequestParam(required = false) BookingStatus status,
                                                     @RequestParam(required = false) String q,
                                                     @RequestParam(required = false) Long cityId,
                                                     @RequestParam(required = false)
                                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                     @RequestParam(required = false)
                                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return service.list(status, q, cityId, from, to, page, size);
    }

    @GetMapping("/{id}")
    public AdminBookingDetailDto detail(@PathVariable Long id) {
        return service.detail(id);
    }

    @PostMapping("/{id}/cancel")
    public AdminBookingDetailDto cancel(@AuthenticationPrincipal AuthUser admin, @PathVariable Long id,
                                        @Valid @RequestBody CancelBookingRequest request) {
        return service.cancel(admin, id, request.reason());
    }
}
