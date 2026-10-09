package com.smartparking.admin.locations;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Coordinates are limited to India's bounding box (latitude 6 to 38, longitude 68 to 98). */
public final class CityRequests {

    private CityRequests() {
    }

    public record Create(
            @NotNull Long stateId,
            @NotBlank @Size(min = 2, max = 100) String name,
            @NotNull @DecimalMin("6") @DecimalMax("38") Double lat,
            @NotNull @DecimalMin("68") @DecimalMax("98") Double lng,
            Boolean capital,
            @Min(1) @Max(3) Integer tier,
            Boolean active) {
    }

    /** Every field is optional; only the ones present change. */
    public record Update(
            @Size(min = 2, max = 100) String name,
            @DecimalMin("6") @DecimalMax("38") Double lat,
            @DecimalMin("68") @DecimalMax("98") Double lng,
            Boolean capital,
            @Min(1) @Max(3) Integer tier,
            Boolean active) {
    }
}
