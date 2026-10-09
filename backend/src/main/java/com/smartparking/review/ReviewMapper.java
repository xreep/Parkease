package com.smartparking.review;

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

    /** First name and the initial of the last name: "Rahul Sharma" becomes "Rahul S."; a single name stays as is. */
    static String authorName(String fullName) {
        String[] parts = fullName == null ? new String[0] : fullName.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return "Driver";
        }
        if (parts.length == 1) {
            return parts[0];
        }
        String last = parts[parts.length - 1];
        return parts[0] + " " + last.substring(0, last.offsetByCodePoints(0, 1)).toUpperCase() + ".";
    }
}
