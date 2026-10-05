package com.smartparking.slot.dto;

import com.smartparking.common.model.VehicleType;
import com.smartparking.slot.SlotSize;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Creates {@code count} slots labelled {@code prefix} + zero-padded number, starting at {@code startNumber}. */
public record BulkSlotRequest(
        @NotBlank @Size(max = 10) @Pattern(regexp = "^[A-Za-z0-9-]*$",
                message = "Use letters, numbers and hyphens only") String prefix,
        @Min(1) @Max(999) int startNumber,
        @Min(1) @Max(50) int count,
        @NotNull VehicleType vehicleType,
        @NotNull SlotSize size) {
}
