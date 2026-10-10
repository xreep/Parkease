package com.smartparking.review.dto;

import java.time.Instant;

/** A review as the listing's owner sees it: the review fields (flat) plus the listing and booking it belongs to. {@code hidden}
 * is true while an admin has taken the review off the public list (it then does not count towards the ratings). */
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
        String bookingCode,
        boolean hidden) {
}
