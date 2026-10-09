package com.smartparking.review.dto;

import com.smartparking.review.ReviewService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The comment's real limit (1000 characters) applies after trimming and is checked by the service. */
public record CreateReviewRequest(
        @NotNull @Min(1) @Max(5) Integer rating,
        @Size(max = ReviewService.RAW_COMMENT_MAX) String comment) {
}
