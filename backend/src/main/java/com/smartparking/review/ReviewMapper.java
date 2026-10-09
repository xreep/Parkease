package com.smartparking.review;

import com.smartparking.common.util.PersonNames;
import com.smartparking.review.dto.OwnerReviewDto;
import com.smartparking.review.dto.ReviewDto;
import org.springframework.stereotype.Component;

/** Maps reviews to DTOs. Call within a transaction (lazy driver, listing and booking). */
@Component
public class ReviewMapper {

    public ReviewDto toDto(Review r) {
        return new ReviewDto(r.getId(), r.getRating(), r.getComment(), authorName(r.getDriver().getName()),
                r.getCreatedAt(), r.getOwnerReply(), r.getOwnerRepliedAt());
    }

    public OwnerReviewDto toOwnerDto(Review r) {
        return new OwnerReviewDto(r.getId(), r.getRating(), r.getComment(), authorName(r.getDriver().getName()),
                r.getCreatedAt(), r.getOwnerReply(), r.getOwnerRepliedAt(), r.getListing().getId(),
                r.getListing().getTitle(), r.getBooking().getBookingCode());
    }

    static String authorName(String fullName) {
        return PersonNames.firstNameLastInitial(fullName);
    }
}
