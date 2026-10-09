package com.smartparking.earning;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OwnerEarningRepository extends JpaRepository<OwnerEarning, Long> {

    Optional<OwnerEarning> findByBookingId(Long bookingId);

    List<OwnerEarning> findByBookingIdIn(Collection<Long> bookingIds);
}
