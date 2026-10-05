package com.smartparking.listing;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParkingListingRepository extends JpaRepository<ParkingListing, Long> {

    Optional<ParkingListing> findByIdAndOwnerId(Long id, Long ownerId);

    Page<ParkingListing> findByOwnerIdOrderByUpdatedAtDesc(Long ownerId, Pageable pageable);

    Page<ParkingListing> findByStatusOrderBySubmittedAtAsc(ListingStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"city", "city.state", "owner"})
    Optional<ParkingListing> findByIdAndStatus(Long id, ListingStatus status);

    @Query("select l from ParkingListing l join fetch l.city c join fetch c.state where l.id in :ids")
    List<ParkingListing> findAllWithCityAndStateByIdIn(@Param("ids") Collection<Long> ids);

    /** Rows of {listingId, amenity} for the given listings, in one query. */
    @Query("select l.id, a from ParkingListing l join l.amenities a where l.id in :ids")
    List<Object[]> findAmenityRowsByListingIdIn(@Param("ids") Collection<Long> ids);

    long countByStatus(ListingStatus status);

    long countByOwnerId(Long ownerId);

    boolean existsByOwnerIdAndTitle(Long ownerId, String title);
}
