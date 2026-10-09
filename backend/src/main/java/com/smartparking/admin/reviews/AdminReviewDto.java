package com.smartparking.admin.reviews;

import java.time.Instant;

/** A review as admins see it: the public review fields (flat) plus its listing, booking and moderation state. */
public record AdminReviewDto(
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
        boolean hidden,
        String hiddenReason,
        Instant hiddenAt) {
}
