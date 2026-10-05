package com.smartparking.listing;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParkingListingRepository extends JpaRepository<ParkingListing, Long> {

    Optional<ParkingListing> findByIdAndOwnerId(Long id, Long ownerId);

    Page<ParkingListing> findByOwnerIdOrderByUpdatedAtDesc(Long ownerId, Pageable pageable);

    Page<ParkingListing> findByStatusOrderBySubmittedAtAsc(ListingStatus status, Pageable pageable);

    long countByStatus(ListingStatus status);

    long countByOwnerId(Long ownerId);

    boolean existsByOwnerIdAndTitle(Long ownerId, String title);
}
