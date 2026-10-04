package com.smartparking.user;

import com.smartparking.common.security.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MeController {

    private final UserService userService;

    @GetMapping
    public UserDto me(@AuthenticationPrincipal AuthUser principal) {
        return userService.get(principal.id());
    }

    @PatchMapping
    public UserDto update(@AuthenticationPrincipal AuthUser principal,
                          @Valid @RequestBody UpdateProfileRequest request) {
        return userService.update(principal.id(), request);
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal AuthUser principal,
                               @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(principal.id(), request);
    }

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resendVerification(@AuthenticationPrincipal AuthUser principal) {
        userService.resendVerification(principal.id());
    }
}
