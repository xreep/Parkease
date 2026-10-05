package com.smartparking.availability;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AvailabilityRuleRepository extends JpaRepository<AvailabilityRule, Long> {

    List<AvailabilityRule> findByListingIdOrderByDayOfWeekAsc(Long listingId);

    List<AvailabilityRule> findByListingIdIn(Collection<Long> listingIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AvailabilityRule r where r.listing.id = :listingId")
    void deleteByListingId(@Param("listingId") Long listingId);
}
