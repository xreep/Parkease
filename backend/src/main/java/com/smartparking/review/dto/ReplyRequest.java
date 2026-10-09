package com.smartparking.review.dto;

import com.smartparking.review.ReviewService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The reply's real limit (500 characters) applies after trimming and is checked by the service. */
public record ReplyRequest(@NotBlank @Size(max = ReviewService.RAW_REPLY_MAX) String reply) {
}
