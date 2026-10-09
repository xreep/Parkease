package com.smartparking.review.dto;

import java.time.Instant;

/** A review as the public sees it; {@code authorName} is the first name and last initial ("Rahul S."). */
public record ReviewDto(
        Long id,
        int rating,
        String comment,
        String authorName,
        Instant createdAt,
        String ownerReply,
        Instant ownerRepliedAt) {
}
