package com.smartparking.user;

import com.smartparking.common.security.AuthUser;
import com.smartparking.common.security.Roles;
import com.smartparking.common.web.PageResponse;
import com.smartparking.user.dto.DriverPaymentDto;
import com.smartparking.user.dto.DriverStatsDto;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in driver's payments and statistics. */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MePaymentsController {

    private final DriverAccountService service;

    @GetMapping("/payments")
    public PageResponse<DriverPaymentDto> payments(@AuthenticationPrincipal AuthUser principal,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        Roles.requireDriver(principal);
        return service.payments(principal.id(), page, size);
    }

    @GetMapping("/stats")
    public DriverStatsDto stats(@AuthenticationPrincipal AuthUser principal) {
        Roles.requireDriver(principal);
        return service.stats(principal.id());
    }
}
