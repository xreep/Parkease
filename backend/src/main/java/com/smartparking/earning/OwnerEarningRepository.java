package com.smartparking.earning;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OwnerEarningRepository extends JpaRepository<OwnerEarning, Long> {

    Optional<OwnerEarning> findByBookingId(Long bookingId);
}
