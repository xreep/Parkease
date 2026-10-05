package com.smartparking.listing;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ListingPhotoRepository extends JpaRepository<ListingPhoto, Long> {

    List<ListingPhoto> findByListingIdOrderBySortOrderAsc(Long listingId);

    List<ListingPhoto> findByListingIdInOrderByListingIdAscSortOrderAsc(Collection<Long> listingIds);

    long countByListingId(Long listingId);

    Optional<ListingPhoto> findByIdAndListingId(Long id, Long listingId);
}
