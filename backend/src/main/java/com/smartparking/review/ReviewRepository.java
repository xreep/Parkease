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

    boolean existsByBookingId(Long bookingId);

    @EntityGraph(attributePaths = {"driver"})
    Optional<Review> findByBookingId(Long bookingId);

    @EntityGraph(attributePaths = {"driver"})
    Page<Review> findByListingId(Long listingId, Pageable pageable);

    @EntityGraph(attributePaths = {"driver", "listing", "booking"})
    Page<Review> findByListingOwnerId(Long ownerId, Pageable pageable);

    @EntityGraph(attributePaths = {"driver", "listing", "booking"})
    Page<Review> findByListingOwnerIdAndListingId(Long ownerId, Long listingId, Pageable pageable);

    Optional<Review> findByIdAndListingOwnerId(Long id, Long ownerId);

    /** Row-locks the review so two replies cannot both pass the "no reply yet" check. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Review r where r.id = :id")
    Optional<Review> findByIdForUpdate(@Param("id") Long id);

    /** Rows of {rating, count} for the listing's reviews. */
    @Query("select r.rating, count(r) from Review r where r.listing.id = :listingId group by r.rating")
    List<Object[]> countByRating(@Param("listingId") Long listingId);
}
