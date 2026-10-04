package com.smartparking.user;

import com.smartparking.common.validation.ValidPassword;
import jakarta.validation.constraints.NotBlank;

public record ChangePasswordRequest(@NotBlank String currentPassword, @ValidPassword String newPassword) {
}
