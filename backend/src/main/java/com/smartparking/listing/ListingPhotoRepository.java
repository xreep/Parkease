package com.smartparking.listing;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ListingPhotoRepository extends JpaRepository<ListingPhoto, Long> {

    List<ListingPhoto> findByListingIdOrderBySortOrderAsc(Long listingId);

    long countByListingId(Long listingId);

    Optional<ListingPhoto> findByIdAndListingId(Long id, Long listingId);
}
