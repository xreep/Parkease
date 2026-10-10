package com.smartparking.dispute.dto;

import com.smartparking.dispute.DisputeResolution;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** {@code amount} is only used by REFUND_PARTIAL. */
public record ResolveRequest(@NotNull DisputeResolution resolution, BigDecimal amount,
                             @NotBlank @Size(max = 1000) String notes) {
}
