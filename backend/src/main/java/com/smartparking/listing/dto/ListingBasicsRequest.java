package com.smartparking.listing.dto;

import com.smartparking.listing.ListingType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ListingBasicsRequest(
        @NotNull Long cityId,
        @NotBlank @Size(max = 120) String title,
        @Size(max = 2000) String description,
        @NotBlank @Size(max = 300) String address,
        @NotBlank @Pattern(regexp = "^[1-9][0-9]{5}$", message = "must be a valid 6-digit PIN code") String pincode,
        @NotNull @DecimalMin("6.0") @DecimalMax("38.0") Double lat,
        @NotNull @DecimalMin("68.0") @DecimalMax("98.0") Double lng,
        @NotNull ListingType listingType) {
}
