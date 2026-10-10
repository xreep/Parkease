package com.smartparking.review.dto;

import com.smartparking.common.web.PageResponse;

/** {@code summary} is only present on the first page (page 0); later pages return {@code null} for it. */
public record ListingReviewsDto(ReviewSummaryDto summary, PageResponse<ReviewDto> reviews) {
}
