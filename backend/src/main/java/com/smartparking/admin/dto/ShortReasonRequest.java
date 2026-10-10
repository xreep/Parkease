package com.smartparking.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A moderation reason of 1 to 300 characters (suspending a user, hiding a review). */
public record ShortReasonRequest(@NotBlank @Size(max = 300) String reason) {
}
