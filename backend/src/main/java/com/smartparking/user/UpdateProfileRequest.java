package com.smartparking.user;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Null fields are left unchanged; an empty phone clears it. */
public record UpdateProfileRequest(
        @Size(min = 1, max = 100) String name,
        @Pattern(regexp = "^([6-9]\\d{9})?$", message = "must be a valid 10-digit Indian mobile number") String phone) {
}
