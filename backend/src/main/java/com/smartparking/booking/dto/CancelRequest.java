package com.smartparking.booking.dto;

import jakarta.validation.constraints.Size;

/** A driver's cancellation; the reason is optional. */
public record CancelRequest(@Size(max = 300) String reason) {
}
