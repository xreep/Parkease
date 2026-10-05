package com.smartparking.owner.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Optional fields may be blank (treated as absent); when present they must match their format.
 * {@code bankAccount} is tri-state: null/absent keeps the stored account, blank clears it, a value replaces it.
 */
public record PayoutRequest(
        @Pattern(regexp = "^(?:[a-zA-Z0-9._-]{2,256}@[a-zA-Z]{2,64})?$", message = "must be a valid UPI ID") String upiId,
        @Pattern(regexp = "^(?:[0-9]{9,18})?$", message = "must be 9 to 18 digits") String bankAccount,
        @Pattern(regexp = "^(?:[A-Z]{4}0[A-Z0-9]{6})?$", message = "must be a valid IFSC code") String ifsc,
        @NotBlank @Size(max = 100) String accountName) {
}
