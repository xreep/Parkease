package com.smartparking.booking;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByIdAndDriverId(Long id, Long driverId);

    Optional<Booking> findByBookingCode(String bookingCode);

    long countByDriverIdAndStatusAndHoldExpiresAtAfter(Long driverId, BookingStatus status, Instant now);

    /** Ids of slots (of the given listings) that a live booking blocks during [start, end). */
    @Query("""
            select distinct b.slot.id from Booking b
            where b.listing.id in :listingIds
              and b.startTime < :end and b.endTime > :start
              and (b.status in (com.smartparking.booking.BookingStatus.AWAITING_APPROVAL,
                                com.smartparking.booking.BookingStatus.CONFIRMED,
                                com.smartparking.booking.BookingStatus.ACTIVE)
                   or (b.status = com.smartparking.booking.BookingStatus.PENDING_PAYMENT
                       and b.holdExpiresAt > :now))
            """)
    List<Long> findLiveOverlappingSlotIds(@Param("listingIds") Collection<Long> listingIds,
                                          @Param("start") Instant start, @Param("end") Instant end,
                                          @Param("now") Instant now);

    /** Marks unpaid holds that already lapsed on the given slots as EXPIRED, freeing the exclusion constraint. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Booking b set b.status = com.smartparking.booking.BookingStatus.EXPIRED, b.updatedAt = :now
            where b.status = com.smartparking.booking.BookingStatus.PENDING_PAYMENT
              and b.holdExpiresAt <= :now and b.slot.id in :slotIds
            """)
    int expireStaleHolds(@Param("slotIds") Collection<Long> slotIds, @Param("now") Instant now);

    List<Booking> findByStatusAndHoldExpiresAtLessThanEqual(BookingStatus status, Instant cutoff);

    List<Booking> findByStatusAndApprovalDeadlineLessThanEqual(BookingStatus status, Instant cutoff);

    Page<Booking> findByDriverId(Long driverId, Pageable pageable);

    Page<Booking> findByDriverIdAndStatusIn(Long driverId, Collection<BookingStatus> statuses, Pageable pageable);

    Page<Booking> findByListingOwnerId(Long ownerId, Pageable pageable);

    Page<Booking> findByListingOwnerIdAndStatusIn(Long ownerId, Collection<BookingStatus> statuses, Pageable pageable);

    Optional<Booking> findByIdAndListingOwnerId(Long id, Long ownerId);
}
