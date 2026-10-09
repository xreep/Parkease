package com.smartparking.review.dto;

import java.time.Instant;

/** A review as the listing's owner sees it: the review fields (flat) plus the listing and booking it belongs to. */
public record OwnerReviewDto(
        Long id,
        int rating,
        String comment,
        String authorName,
        Instant createdAt,
        String ownerReply,
        Instant ownerRepliedAt,
        Long listingId,
        String listingTitle,
        String bookingCode) {
}
