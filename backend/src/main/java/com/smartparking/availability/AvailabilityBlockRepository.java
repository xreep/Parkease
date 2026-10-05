package com.smartparking.availability;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AvailabilityBlockRepository extends JpaRepository<AvailabilityBlock, Long> {

    List<AvailabilityBlock> findByListingIdAndEndTimeAfterOrderByStartTimeAsc(Long listingId, Instant after);

    /** Blocks of the given listings that overlap the half-open window [start, end). */
    @Query("select b from AvailabilityBlock b where b.listing.id in :listingIds "
            + "and b.startTime < :end and b.endTime > :start")
    List<AvailabilityBlock> findOverlapping(@Param("listingIds") Collection<Long> listingIds,
            @Param("start") Instant start, @Param("end") Instant end);

    Optional<AvailabilityBlock> findByIdAndListingId(Long id, Long listingId);
}
