package com.smartparking.availability;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AvailabilityBlockRepository extends JpaRepository<AvailabilityBlock, Long> {

    List<AvailabilityBlock> findByListingIdAndEndTimeAfterOrderByStartTimeAsc(Long listingId, Instant after);

    Optional<AvailabilityBlock> findByIdAndListingId(Long id, Long listingId);
}
