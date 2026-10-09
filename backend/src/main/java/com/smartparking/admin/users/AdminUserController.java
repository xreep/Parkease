package com.smartparking.admin.users;

import com.smartparking.admin.dto.ShortReasonRequest;
import com.smartparking.common.security.AuthUser;
import com.smartparking.common.web.PageResponse;
import com.smartparking.user.Role;
import com.smartparking.user.UserStatus;
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

@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService service;

    @GetMapping
    public PageResponse<AdminUserDto> list(@RequestParam(required = false) Role role,
                                           @RequestParam(required = false) UserStatus status,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return service.list(role, status, q, page, size);
    }

    @PostMapping("/{id}/suspend")
    public AdminUserDto suspend(@AuthenticationPrincipal AuthUser admin, @PathVariable Long id,
                                @Valid @RequestBody ShortReasonRequest request) {
        return service.suspend(admin, id, request.reason());
    }

    @PostMapping("/{id}/activate")
    public AdminUserDto activate(@AuthenticationPrincipal AuthUser admin, @PathVariable Long id) {
        return service.activate(admin, id);
    }
}
