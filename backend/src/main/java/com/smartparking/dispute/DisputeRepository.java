package com.smartparking.dispute;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DisputeRepository extends JpaRepository<Dispute, Long> {

    boolean existsByBookingIdAndStatusNot(Long bookingId, DisputeStatus status);

    @EntityGraph(attributePaths = {"booking", "booking.listing", "raisedBy"})
    List<Dispute> findByBookingIdOrderByCreatedAtDescIdDesc(Long bookingId);

    @EntityGraph(attributePaths = {"booking", "booking.listing", "raisedBy"})
    Page<Dispute> findByRaisedById(Long driverId, Pageable pageable);

    @EntityGraph(attributePaths = {"booking", "booking.listing", "raisedBy"})
    Optional<Dispute> findByIdAndRaisedById(Long id, Long driverId);

    @EntityGraph(attributePaths = {"booking", "booking.listing", "raisedBy"})
    @Query("""
            select d from Dispute d
            where d.booking.listing.owner.id = :ownerId and (:status is null or d.status = :status)""")
    Page<Dispute> findForOwner(@Param("ownerId") Long ownerId, @Param("status") DisputeStatus status,
                               Pageable pageable);

    @EntityGraph(attributePaths = {"booking", "booking.listing", "raisedBy"})
    Optional<Dispute> findByIdAndBookingListingOwnerId(Long id, Long ownerId);

    @EntityGraph(attributePaths = {"booking", "booking.listing", "raisedBy"})
    @Query("select d from Dispute d where (:status is null or d.status = :status)")
    Page<Dispute> findForAdmin(@Param("status") DisputeStatus status, Pageable pageable);

    @Query("select d.booking.id from Dispute d where d.id = :id")
    Optional<Long> findBookingIdById(@Param("id") Long id);

    /** Row-locks the dispute so two decisions (or a decision and a response) cannot interleave. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Dispute d where d.id = :id")
    Optional<Dispute> findByIdForUpdate(@Param("id") Long id);
}
