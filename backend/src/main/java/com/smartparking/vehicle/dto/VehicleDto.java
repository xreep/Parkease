package com.smartparking.vehicle.dto;

import com.smartparking.common.model.VehicleType;

public record VehicleDto(Long id, VehicleType type, String plateNumber, String makeModel, boolean isDefault) {
}
