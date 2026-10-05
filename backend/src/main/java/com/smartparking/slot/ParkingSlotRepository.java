package com.smartparking.slot;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParkingSlotRepository extends JpaRepository<ParkingSlot, Long> {

    List<ParkingSlot> findByListingIdOrderByLabelAsc(Long listingId);

    List<ParkingSlot> findByListingIdInAndActiveTrue(Collection<Long> listingIds);

    long countByListingId(Long listingId);

    long countByListingIdAndActiveTrue(Long listingId);

    boolean existsByListingIdAndLabelIgnoreCase(Long listingId, String label);

    Optional<ParkingSlot> findByIdAndListingId(Long id, Long listingId);
}
