package com.smartparking.vehicle;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    List<Vehicle> findByUserIdOrderByIsDefaultDescCreatedAtAsc(Long userId);

    Optional<Vehicle> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndPlateNumber(Long userId, String plateNumber);

    long countByUserId(Long userId);
}
