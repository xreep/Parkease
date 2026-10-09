package com.smartparking.settings;

import com.smartparking.common.security.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read and edit the platform settings (ADMIN only, enforced by the security rules for /admin/**). */
@RestController
@RequestMapping("/api/v1/admin/settings")
@RequiredArgsConstructor
public class AdminSettingsController {

    private final PlatformSettings settings;

    @GetMapping
    public PlatformSettingsDto get() {
        return settings.current();
    }

    @PutMapping
    public PlatformSettingsDto update(@AuthenticationPrincipal AuthUser admin,
                                      @Valid @RequestBody PlatformSettingsDto request) {
        return settings.update(admin, request);
    }
}
