package com.smartparking.booking;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /** The slot and time span of a booking, without loading the booking itself. */
    interface SlotWindow {
        Long getSlotId();

        Instant getStartTime();

        Instant getEndTime();
    }

    /** Start time and status of a booking. */
    interface StartAndStatus {
        Instant getStartTime();

        BookingStatus getStatus();
    }

    /** Listing, slot and time span of a booking. */
    interface ListingSlotWindow {
        Long getListingId();

        Long getSlotId();

        Instant getStartTime();

        Instant getEndTime();
    }

    /** Slot and time span of the listing's live bookings that overlap [start, end), in one query. */
    @Query("""
            select b.slot.id as slotId, b.startTime as startTime, b.endTime as endTime from Booking b
            where b.listing.id = :listingId
              and b.startTime < :end and b.endTime > :start
              and (b.status in (com.smartparking.booking.BookingStatus.AWAITING_APPROVAL,
                                com.smartparking.booking.BookingStatus.CONFIRMED,
                                com.smartparking.booking.BookingStatus.ACTIVE)
                   or (b.status = com.smartparking.booking.BookingStatus.PENDING_PAYMENT
                       and b.holdExpiresAt > :now))
            """)
    List<SlotWindow> findLiveWindows(@Param("listingId") Long listingId, @Param("start") Instant start,
                                     @Param("end") Instant end, @Param("now") Instant now);

    Optional<Booking> findByIdAndDriverId(Long id, Long driverId);

    /** Row-locks the booking so status checks and transitions cannot interleave with the stale-hold sweep. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") Long id);

    boolean existsByIdAndDriverId(Long id, Long driverId);

    @Query("select b.slot.id from Booking b where b.id = :id")
    Long findSlotIdById(@Param("id") Long id);

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

    /** Ids of unpaid holds that lapsed at or before {@code cutoff}, oldest first. */
    @Query("""
            select b.id from Booking b
            where b.status = com.smartparking.booking.BookingStatus.PENDING_PAYMENT and b.holdExpiresAt <= :cutoff
            order by b.id
            """)
    List<Long> findLapsedHoldIds(@Param("cutoff") Instant cutoff, Limit limit);

    /**
     * Ids of paid requests that can no longer be answered at {@code cutoff}: the owner response deadline or the start
     * time passed (older requests may have a deadline after their start), oldest first.
     */
    @Query("""
            select b.id from Booking b
            where b.status = com.smartparking.booking.BookingStatus.AWAITING_APPROVAL
              and (b.approvalDeadline <= :cutoff or b.startTime <= :cutoff)
            order by b.id
            """)
    List<Long> findOverdueApprovalIds(@Param("cutoff") Instant cutoff, Limit limit);

    /** Ids of confirmed bookings whose parking time is under way at {@code now}, earliest start first. */
    @Query("""
            select b.id from Booking b
            where b.status = com.smartparking.booking.BookingStatus.CONFIRMED
              and b.startTime <= :now and b.endTime > :now
            order by b.startTime, b.id
            """)
    List<Long> findDueToStartIds(@Param("now") Instant now, Limit limit);

    /** Ids of confirmed or active bookings whose parking time is over at {@code now}, earliest end first. */
    @Query("""
            select b.id from Booking b
            where b.status in (com.smartparking.booking.BookingStatus.CONFIRMED,
                               com.smartparking.booking.BookingStatus.ACTIVE)
              and b.endTime <= :now
            order by b.endTime, b.id
            """)
    List<Long> findDueToCompleteIds(@Param("now") Instant now, Limit limit);

    /** Confirmed bookings starting within (now, horizon] that have not had their reminder yet, earliest first. */
    @Query("""
            select b.id from Booking b
            where b.status = com.smartparking.booking.BookingStatus.CONFIRMED and b.reminderSentAt is null
              and b.startTime > :now and b.startTime <= :horizon
            order by b.startTime, b.id
            """)
    List<Long> findDueForReminderIds(@Param("now") Instant now, @Param("horizon") Instant horizon, Limit limit);

    /** Requests whose approval deadline falls within (now, horizon] and whose owner was not nudged yet. */
    @Query("""
            select b.id from Booking b
            where b.status = com.smartparking.booking.BookingStatus.AWAITING_APPROVAL and b.approvalNudgeSentAt is null
              and b.approvalDeadline > :now and b.approvalDeadline <= :horizon
            order by b.approvalDeadline, b.id
            """)
    List<Long> findDueForApprovalNudgeIds(@Param("now") Instant now, @Param("horizon") Instant horizon, Limit limit);

    // ---- owner views (never PENDING_PAYMENT or EXPIRED: those were never paid) ----------------------------------

    boolean existsByIdAndListingOwnerId(Long id, Long ownerId);

    Page<Booking> findByListingOwnerId(Long ownerId, Pageable pageable);

    @EntityGraph(attributePaths = {"listing", "slot", "driver"})
    Page<Booking> findByListingOwnerIdAndStatusIn(Long ownerId, Collection<BookingStatus> statuses, Pageable pageable);

    /** Paid, still-relevant bookings of the owner's listings: CONFIRMED or ACTIVE that have not ended yet. */
    @EntityGraph(attributePaths = {"listing", "slot", "driver"})
    @Query("""
            select b from Booking b
            where b.listing.owner.id = :ownerId and b.endTime > :now
              and b.status in (com.smartparking.booking.BookingStatus.CONFIRMED,
                               com.smartparking.booking.BookingStatus.ACTIVE)
            """)
    Page<Booking> findUpcomingForOwner(@Param("ownerId") Long ownerId, @Param("now") Instant now, Pageable pageable);

    /** Finished or unwound bookings of the owner's listings (not requests, not upcoming, never unpaid ones). */
    @EntityGraph(attributePaths = {"listing", "slot", "driver"})
    @Query("""
            select b from Booking b
            where b.listing.owner.id = :ownerId
              and (b.status in (com.smartparking.booking.BookingStatus.COMPLETED,
                                com.smartparking.booking.BookingStatus.CANCELLED,
                                com.smartparking.booking.BookingStatus.REJECTED)
                   or (b.status in (com.smartparking.booking.BookingStatus.CONFIRMED,
                                    com.smartparking.booking.BookingStatus.ACTIVE)
                       and b.endTime <= :now))
            """)
    Page<Booking> findPastForOwner(@Param("ownerId") Long ownerId, @Param("now") Instant now, Pageable pageable);

    // ---- driver views ---------------------------------------------------------------------------------------

    /** Bookings that still lie ahead: not ended, and either paid for or on an unexpired payment hold. */
    @EntityGraph(attributePaths = {"listing", "listing.city"})
    @Query("""
            select b from Booking b
            where b.driver.id = :driverId and b.endTime > :now
              and (b.status in (com.smartparking.booking.BookingStatus.AWAITING_APPROVAL,
                                com.smartparking.booking.BookingStatus.CONFIRMED,
                                com.smartparking.booking.BookingStatus.ACTIVE)
                   or (b.status = com.smartparking.booking.BookingStatus.PENDING_PAYMENT and b.holdExpiresAt > :now))
            """)
    Page<Booking> findUpcomingForDriver(@Param("driverId") Long driverId, @Param("now") Instant now, Pageable pageable);

    /** Everything that is not upcoming: ended, finished, cancelled, rejected, or an unpaid hold that lapsed. */
    @EntityGraph(attributePaths = {"listing", "listing.city"})
    @Query("""
            select b from Booking b
            where b.driver.id = :driverId
              and (b.endTime <= :now
                   or b.status in (com.smartparking.booking.BookingStatus.COMPLETED,
                                   com.smartparking.booking.BookingStatus.CANCELLED,
                                   com.smartparking.booking.BookingStatus.REJECTED,
                                   com.smartparking.booking.BookingStatus.EXPIRED)
                   or (b.status = com.smartparking.booking.BookingStatus.PENDING_PAYMENT
                       and (b.holdExpiresAt is null or b.holdExpiresAt <= :now)))
            """)
    Page<Booking> findPastForDriver(@Param("driverId") Long driverId, @Param("now") Instant now, Pageable pageable);

    @EntityGraph(attributePaths = {"listing", "listing.city"})
    Page<Booking> findByDriverId(Long driverId, Pageable pageable);

    Optional<Booking> findByIdAndListingOwnerId(Long id, Long ownerId);

    // ---- owner dashboard ------------------------------------------------------------------------------------

    long countByListingOwnerIdAndStatus(Long ownerId, BookingStatus status);

    /** The owner's bookings in the given statuses that start after {@code after}; the page's sort picks the order. */
    @EntityGraph(attributePaths = {"listing", "slot", "driver"})
    Page<Booking> findByListingOwnerIdAndStatusInAndStartTimeAfter(Long ownerId, Collection<BookingStatus> statuses,
                                                                   Instant after, Pageable pageable);

    /** Start and status of the owner's bookings in the given statuses that start in [from, to). */
    @Query("""
            select b.startTime as startTime, b.status as status from Booking b
            where b.listing.owner.id = :ownerId and b.status in :statuses
              and b.startTime >= :from and b.startTime < :to
            """)
    List<StartAndStatus> findStartsInRange(@Param("ownerId") Long ownerId,
                                           @Param("statuses") Collection<BookingStatus> statuses,
                                           @Param("from") Instant from, @Param("to") Instant to);

    /** Booked spans (CONFIRMED, ACTIVE, COMPLETED) of the owner's APPROVED listings overlapping [from, to). */
    @Query("""
            select b.listing.id as listingId, b.slot.id as slotId, b.startTime as startTime, b.endTime as endTime
            from Booking b
            where b.listing.owner.id = :ownerId
              and b.listing.status = com.smartparking.listing.ListingStatus.APPROVED
              and b.status in (com.smartparking.booking.BookingStatus.CONFIRMED,
                               com.smartparking.booking.BookingStatus.ACTIVE,
                               com.smartparking.booking.BookingStatus.COMPLETED)
              and b.startTime < :to and b.endTime > :from
            """)
    List<ListingSlotWindow> findBookedWindows(@Param("ownerId") Long ownerId, @Param("from") Instant from,
                                              @Param("to") Instant to);

    /** Live and completed bookings of one listing overlapping [from, to), for the owner's calendar. */
    @EntityGraph(attributePaths = {"slot", "driver"})
    @Query("""
            select b from Booking b
            where b.listing.id = :listingId and b.startTime < :to and b.endTime > :from
              and (b.status in (com.smartparking.booking.BookingStatus.AWAITING_APPROVAL,
                                com.smartparking.booking.BookingStatus.CONFIRMED,
                                com.smartparking.booking.BookingStatus.ACTIVE,
                                com.smartparking.booking.BookingStatus.COMPLETED)
                   or (b.status = com.smartparking.booking.BookingStatus.PENDING_PAYMENT and b.holdExpiresAt > :now))
            order by b.startTime, b.id
            """)
    List<Booking> findForCalendar(@Param("listingId") Long listingId, @Param("from") Instant from,
                                  @Param("to") Instant to, @Param("now") Instant now);
}
