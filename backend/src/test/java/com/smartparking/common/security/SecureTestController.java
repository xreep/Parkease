package com.smartparking.common.security;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/test-secure")
class SecureTestController {

    @GetMapping("/me")
    AuthUser me(@AuthenticationPrincipal AuthUser user) {
        return user;
    }

    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    String admin() {
        return "ok";
    }
}
