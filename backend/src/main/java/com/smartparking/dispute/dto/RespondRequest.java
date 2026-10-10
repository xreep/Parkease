package com.smartparking.dispute.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RespondRequest(@NotBlank @Size(max = 1000) String response) {
}
