package com.smartparking.auth.dto;

import com.smartparking.common.validation.ValidPassword;
import jakarta.validation.constraints.NotBlank;

public record ResetPasswordRequest(@NotBlank String token, @ValidPassword String password) {
}
