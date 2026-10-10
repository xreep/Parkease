package com.smartparking.admin.reviews;

import com.smartparking.admin.audit.AdminAuditService;
import com.smartparking.common.error.ApiException;
import com.smartparking.common.security.AuthUser;
import com.smartparking.common.web.PageResponse;
import com.smartparking.listing.ParkingListing;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.review.Review;
import com.smartparking.review.ReviewRepository;
import com.smartparking.review.ReviewService;
import com.smartparking.common.util.PersonNames;
import java.time.Clock;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Review moderation: hiding takes a review off the public list and out of the rating aggregates. */
@Service
@RequiredArgsConstructor
public class AdminReviewModerationService {

    private final ReviewRepository reviews;
    private final ParkingListingRepository listings;
    private final ReviewService reviewService;
    private final AdminAuditService audit;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<AdminReviewDto> list(Boolean hidden, String q, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        int mode = hidden == null ? 0 : hidden ? 1 : 2;
        return PageResponse.from(reviews.adminSearch(mode, likePattern(q), pageable).map(this::toDto));
    }

    @Transactional
    public AdminReviewDto hide(AuthUser admin, Long id, String rawReason) {
        Review review = lockedReview(id);
        if (review.isHidden()) {
            throw ApiException.conflict("INVALID_STATUS", "This review is already hidden");
        }
        String reason = rawReason.trim();
        review.setHiddenAt(clock.instant());
        review.setHiddenReason(reason);
        reviews.saveAndFlush(review);
        refresh(review);
        audit.record(admin, "REVIEW_HIDDEN", "REVIEW", id, "Reason: " + reason);
        return toDto(review);
    }

    @Transactional
    public AdminReviewDto unhide(AuthUser admin, Long id) {
        Review review = lockedReview(id);
        if (!review.isHidden()) {
            throw ApiException.conflict("INVALID_STATUS", "This review is not hidden");
        }
        review.setHiddenAt(null);
        review.setHiddenReason(null);
        reviews.saveAndFlush(review);
        refresh(review);
        audit.record(admin, "REVIEW_UNHIDDEN", "REVIEW", id, null);
        return toDto(review);
    }

    /** Locks the listing row (as posting a review does), then the review, and returns the review. */
    private Review lockedReview(Long id) {
        Long listingId = reviews.findListingIdById(id).orElseThrow(() -> ApiException.notFound("Review not found"));
        listings.findByIdForUpdate(listingId).orElseThrow(() -> ApiException.notFound("Review not found"));
        return reviews.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("Review not found"));
    }

    private void refresh(Review review) {
        ParkingListing listing = listings.findByIdForUpdate(review.getListing().getId()).orElseThrow();
        reviewService.refreshAggregates(listing);
        listings.save(listing);
    }

    private AdminReviewDto toDto(Review r) {
        return new AdminReviewDto(r.getId(), r.getRating(), r.getComment(),
                PersonNames.firstNameLastInitial(r.getDriver().getName()), r.getCreatedAt(), r.getOwnerReply(),
                r.getOwnerRepliedAt(), r.getListing().getId(), r.getListing().getTitle(),
                r.getBooking().getBookingCode(), r.isHidden(), r.getHiddenReason(), r.getHiddenAt());
    }

    private static String likePattern(String q) {
        if (q == null || q.isBlank()) {
            return "%";
        }
        return "%" + q.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                + "%";
    }
}
