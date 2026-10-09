package com.smartparking.review.dto;

import com.smartparking.common.web.PageResponse;

public record ListingReviewsDto(ReviewSummaryDto summary, PageResponse<ReviewDto> reviews) {
}
