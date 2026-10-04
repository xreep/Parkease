package com.smartparking.slot.dto;

import com.smartparking.common.model.VehicleType;
import com.smartparking.slot.SlotSize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A null {@code active} means active. The label is trimmed by the service. */
public record SlotRequest(
        @NotBlank @Size(max = 20) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9 -]*$",
                message = "Use letters, numbers, spaces and hyphens only") String label,
        @NotNull VehicleType vehicleType,
        @NotNull SlotSize size,
        Boolean active) {
}
