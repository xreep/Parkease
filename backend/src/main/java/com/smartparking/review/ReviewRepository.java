package com.smartparking.review;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    /** Sum of the ratings and number of reviews. */
    interface RatingTotals {
        long getRatingSum();

        long getRatingCount();
    }

    /** All visible (not hidden) reviews of the owner's listings added up. */
    @Query("""
            select coalesce(sum(r.rating), 0) as ratingSum, count(r) as ratingCount
            from Review r where r.listing.owner.id = :ownerId and r.hiddenAt is null
            """)
    RatingTotals totalsForOwner(@Param("ownerId") Long ownerId);

    boolean existsByBookingId(Long bookingId);

    @EntityGraph(attributePaths = {"driver"})
    Optional<Review> findByBookingId(Long bookingId);

    /** The public list: hidden reviews are left out. */
    @EntityGraph(attributePaths = {"driver"})
    Page<Review> findByListingIdAndHiddenAtIsNull(Long listingId, Pageable pageable);

    @EntityGraph(attributePaths = {"driver", "listing", "booking"})
    Page<Review> findByListingOwnerId(Long ownerId, Pageable pageable);

    @EntityGraph(attributePaths = {"driver", "listing", "booking"})
    Page<Review> findByListingOwnerIdAndListingId(Long ownerId, Long listingId, Pageable pageable);

    Optional<Review> findByIdAndListingOwnerId(Long id, Long ownerId);

    @Query("select r.listing.id from Review r where r.id = :id")
    Optional<Long> findListingIdById(@Param("id") Long id);

    /**
     * Admin search. {@code hiddenMode}: 0 = all, 1 = hidden only, 2 = visible only. {@code pattern} is a lower-cased
     * LIKE pattern (backslash-escaped) matched against the comment, listing title, booking code and author.
     */
    @EntityGraph(attributePaths = {"driver", "listing", "booking"})
    @Query(value = """
            select r from Review r
            where (:hiddenMode = 0 or (:hiddenMode = 1 and r.hiddenAt is not null)
                                   or (:hiddenMode = 2 and r.hiddenAt is null))
              and (lower(coalesce(r.comment, '')) like :pattern escape '\\'
                   or lower(r.listing.title) like :pattern escape '\\'
                   or lower(r.booking.bookingCode) like :pattern escape '\\'
                   or lower(r.driver.name) like :pattern escape '\\'
                   or lower(r.driver.email) like :pattern escape '\\')
            """, countQuery = """
            select count(r) from Review r
            where (:hiddenMode = 0 or (:hiddenMode = 1 and r.hiddenAt is not null)
                                   or (:hiddenMode = 2 and r.hiddenAt is null))
              and (lower(coalesce(r.comment, '')) like :pattern escape '\\'
                   or lower(r.listing.title) like :pattern escape '\\'
                   or lower(r.booking.bookingCode) like :pattern escape '\\'
                   or lower(r.driver.name) like :pattern escape '\\'
                   or lower(r.driver.email) like :pattern escape '\\')
            """)
    Page<Review> adminSearch(@Param("hiddenMode") int hiddenMode, @Param("pattern") String pattern,
                             Pageable pageable);

    /** Row-locks the review so two replies cannot both pass the "no reply yet" check. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Review r where r.id = :id")
    Optional<Review> findByIdForUpdate(@Param("id") Long id);

    /** Rows of {rating, count} for the listing's visible (not hidden) reviews. */
    @Query("select r.rating, count(r) from Review r where r.listing.id = :listingId and r.hiddenAt is null "
            + "group by r.rating")
    List<Object[]> countByRating(@Param("listingId") Long listingId);
}
