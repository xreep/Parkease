package com.smartparking.booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** An owner's cancellation of a confirmed booking; the driver is told the reason. */
public record OwnerCancelRequest(@NotBlank @Size(max = 300) String reason) {
}
