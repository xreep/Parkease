package com.smartparking.review;

import com.smartparking.booking.Booking;
import com.smartparking.booking.BookingLocks;
import com.smartparking.booking.BookingRepository;
import com.smartparking.booking.BookingStatus;
import com.smartparking.common.config.AppProperties;
import com.smartparking.common.error.ApiException;
import com.smartparking.common.error.FieldErrorDto;
import com.smartparking.common.util.SqlStates;
import com.smartparking.common.web.PageResponse;
import com.smartparking.email.EmailTemplates;
import com.smartparking.listing.ListingStatus;
import com.smartparking.listing.ParkingListing;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.notification.NotificationType;
import com.smartparking.notification.Notifier;
import com.smartparking.review.dto.ListingReviewsDto;
import com.smartparking.review.dto.OwnerReviewDto;
import com.smartparking.review.dto.ReviewDto;
import com.smartparking.review.dto.ReviewSummaryDto;
import com.smartparking.user.UserStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reviews of completed bookings. Posting locks payment, then booking (the shared lock order), then the listing row, so
 * concurrent reviews of one booking cannot both pass the duplicate check and the listing's rating aggregate is always
 * recomputed from a consistent set of reviews.
 */
@Service
@RequiredArgsConstructor
public class ReviewService {

    /** Limits on the trimmed text. */
    public static final int COMMENT_MAX = 1000;
    public static final int REPLY_MAX = 500;
    /** Generous limits on the raw request body, so stray whitespace never causes a rejection. */
    public static final int RAW_COMMENT_MAX = 5000;
    public static final int RAW_REPLY_MAX = 2000;

    static final String OWNER_PATH = "/owner/reviews";

    private final ReviewRepository reviews;
    private final BookingRepository bookings;
    private final BookingLocks locks;
    private final ParkingListingRepository listings;
    private final ReviewMapper mapper;
    private final Notifier notifier;
    private final AppProperties app;
    private final Clock clock;

    // ---- driver ---------------------------------------------------------------------------------------------

    @Transactional
    public ReviewDto create(Long driverId, Long bookingId, int rating, String rawComment) {
        String comment = rawComment == null || rawComment.isBlank() ? null : rawComment.trim();
        requireMaxLength("comment", comment, COMMENT_MAX);
        bookings.findByIdAndDriverId(bookingId, driverId).orElseThrow(() -> ApiException.notFound("Booking not found"));
        Booking booking = locks.lock(bookingId);
        if (booking.getStatus() == BookingStatus.COMPLETED && reviews.existsByBookingId(bookingId)) {
            throw alreadyReviewed();
        }
        String reason = ReviewPolicy.notReviewableReason(booking, clock.instant());
        if (reason != null) {
            throw ApiException.conflict("NOT_REVIEWABLE", reason);
        }
        ParkingListing listing = listings.findByIdForUpdate(booking.getListing().getId()).orElseThrow();

        Review review = new Review();
        review.setBooking(booking);
        review.setListing(listing);
        review.setDriver(booking.getDriver());
        review.setRating((short) rating);
        review.setComment(comment);
        try {
            reviews.saveAndFlush(review);
        } catch (DataIntegrityViolationException e) {
            if (SqlStates.UNIQUE_VIOLATION.equals(SqlStates.of(e))) {
                throw alreadyReviewed();
            }
            throw e;
        }

        refreshAggregates(listing);

        String link = app.frontendUrl() + OWNER_PATH;
        notifier.notify(listing.getOwner(), NotificationType.OWNER_NEW_REVIEW,
                "New " + rating + "★ review for " + listing.getTitle(),
                comment != null ? comment : "A driver rated " + listing.getTitle() + " " + rating + " out of 5.",
                OWNER_PATH, EmailTemplates.ownerNewReview(listing.getOwner(), listing.getTitle(), rating, comment, link));
        return mapper.toDto(review);
    }

    /** Fails with the same 400 shape as bean validation when the trimmed text is too long. */
    private static void requireMaxLength(String field, String text, int max) {
        if (text != null && text.length() > max) {
            String message = "must be at most " + max + " characters";
            throw ApiException.badRequest("VALIDATION_FAILED", "Some fields are invalid")
                    .with("fieldErrors", List.of(new FieldErrorDto(field, message)));
        }
    }

    private static ApiException alreadyReviewed() {
        return ApiException.conflict("ALREADY_REVIEWED", "You have already reviewed this booking");
    }

    // ---- public ---------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ListingReviewsDto listForListing(Long listingId, int page, int size) {
        ParkingListing listing = listings.findById(listingId)
                .filter(l -> l.getStatus() == ListingStatus.APPROVED || l.getStatus() == ListingStatus.PAUSED)
                .filter(l -> l.getOwner().getStatus() == UserStatus.ACTIVE) // a suspended owner's listings are hidden
                .orElseThrow(() -> ApiException.notFound("Listing not found"));
        Page<Review> result = reviews.findByListingIdAndHiddenAtIsNull(listing.getId(), paged(page, size));
        // The summary (one grouped query) is the same on every page, so only the first page pays for it.
        ReviewSummaryDto summary = result.getNumber() == 0 ? summarize(listing.getId()) : null;
        return new ListingReviewsDto(summary, new PageResponse<>(
                result.getContent().stream().map(mapper::toDto).toList(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages()));
    }

    // ---- owner ----------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<OwnerReviewDto> listForOwner(Long ownerId, Long listingId, int page, int size) {
        Pageable pageable = paged(page, size);
        Page<Review> result;
        if (listingId == null) {
            result = reviews.findByListingOwnerId(ownerId, pageable);
        } else {
            listings.findByIdAndOwnerId(listingId, ownerId).orElseThrow(() -> ApiException.notFound("Listing not found"));
            result = reviews.findByListingOwnerIdAndListingId(ownerId, listingId, pageable);
        }
        return new PageResponse<>(result.getContent().stream().map(mapper::toOwnerDto).toList(), result.getNumber(),
                result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public ReviewDto reply(Long ownerId, Long reviewId, String rawReply) {
        String reply = rawReply.trim();
        requireMaxLength("reply", reply, REPLY_MAX);
        reviews.findByIdAndListingOwnerId(reviewId, ownerId).orElseThrow(() -> ApiException.notFound("Review not found"));
        Review review = reviews.findByIdForUpdate(reviewId).orElseThrow(() -> ApiException.notFound("Review not found"));
        if (review.getOwnerReply() != null) {
            throw ApiException.conflict("ALREADY_REPLIED", "You have already replied to this review");
        }
        review.setOwnerReply(reply);
        review.setOwnerRepliedAt(clock.instant());
        return mapper.toDto(review);
    }

    // ---- shared ---------------------------------------------------------------------------------------------

    /**
     * Recomputes the listing's rating aggregates from its visible (not hidden) reviews. The caller holds the listing's
     * row lock (see {@link ParkingListingRepository#findByIdForUpdate}), so concurrent changes cannot interleave.
     */
    public void refreshAggregates(ParkingListing listing) {
        ReviewSummaryDto summary = summarize(listing.getId());
        listing.setAvgRating(summary.avgRating());
        listing.setReviewCount(summary.reviewCount());
    }

    /** Average (one decimal, HALF_UP), count and per-star distribution of the listing's visible review rows. */
    private ReviewSummaryDto summarize(Long listingId) {
        Map<String, Integer> distribution = new LinkedHashMap<>();
        for (int star = 1; star <= 5; star++) {
            distribution.put(String.valueOf(star), 0);
        }
        long total = 0;
        int count = 0;
        List<Object[]> rows = reviews.countByRating(listingId);
        for (Object[] row : rows) {
            int star = ((Number) row[0]).intValue();
            int n = ((Number) row[1]).intValue();
            distribution.put(String.valueOf(star), n);
            total += (long) star * n;
            count += n;
        }
        BigDecimal avg = count == 0 ? BigDecimal.ZERO.setScale(1)
                : BigDecimal.valueOf(total).divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP);
        return new ReviewSummaryDto(avg, count, distribution);
    }

    private static Pageable paged(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }
}
