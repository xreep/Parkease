package com.smartparking.dispute.dto;

import com.smartparking.dispute.DisputeCategory;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateDisputeRequest(@NotNull DisputeCategory category,
                                   @NotNull @Size(min = 10, max = 2000) String description) {
}
