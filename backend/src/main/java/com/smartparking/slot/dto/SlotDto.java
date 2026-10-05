package com.smartparking.slot.dto;

import com.smartparking.common.model.VehicleType;
import com.smartparking.slot.SlotSize;

public record SlotDto(Long id, String label, VehicleType vehicleType, SlotSize size, boolean active) {
}
