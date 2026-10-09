package com.smartparking.vehicle.dto;

import com.smartparking.common.model.VehicleType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record VehicleRequest(
        @NotNull VehicleType type,
        @NotBlank @Size(max = 30) String plateNumber,
        @Size(max = 60) String makeModel,
        Boolean isDefault) {
}
