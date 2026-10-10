package com.smartparking.listing;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /** Row-locks the listing so rating aggregates are recomputed one review at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from ParkingListing l where l.id = :id")
    Optional<ParkingListing> findByIdForUpdate(@Param("id") Long id);

    boolean existsByIdAndOwnerId(Long id, Long ownerId);

    List<ParkingListing> findByOwnerId(Long ownerId);

    long countByStatus(ListingStatus status);

    long countByOwnerId(Long ownerId);

    long countByCityId(Long cityId);

    /** Rows of {cityId, count} of the cities' listings in any status. */
    @Query("select l.city.id, count(l) from ParkingListing l where l.city.id in :ids group by l.city.id")
    List<Object[]> countByCities(@Param("ids") Collection<Long> ids);

    /** Rows of {ownerId, count} of the owners' listings in any status. */
    @Query("select l.owner.id, count(l) from ParkingListing l where l.owner.id in :ids group by l.owner.id")
    List<Object[]> countByOwners(@Param("ids") Collection<Long> ids);

    boolean existsByOwnerIdAndTitle(Long ownerId, String title);
}
