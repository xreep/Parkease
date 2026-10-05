package com.smartparking.listing.dto;

import com.smartparking.listing.Amenity;
import com.smartparking.listing.CancellationPolicy;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Set;

public record PricingRequest(
        @NotNull @DecimalMin("1.00") @DecimalMax("10000.00") @Digits(integer = 8, fraction = 2) BigDecimal pricePerHour,
        @DecimalMin("1.00") @DecimalMax("100000.00") @Digits(integer = 8, fraction = 2) BigDecimal pricePerDay,
        @DecimalMin("1.00") @DecimalMax("1000000.00") @Digits(integer = 8, fraction = 2) BigDecimal pricePerMonth,
        @NotNull CancellationPolicy cancellationPolicy,
        @NotNull Boolean autoApprove,
        @NotNull Set<Amenity> amenities,
        @Size(max = 2000) String rules) {
}
