package com.smartparking.earning;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OwnerEarningRepository
        extends JpaRepository<OwnerEarning, Long>, JpaSpecificationExecutor<OwnerEarning> {

    /** Sum of the net and number of earnings in one status. */
    interface StatusTotal {
        EarningStatus getStatus();

        BigDecimal getNet();

        long getRows();
    }

    /** Net of an earning and the start of its booking. */
    interface DatedNet {
        Instant getStartTime();

        BigDecimal getNet();
    }

    /** An owner's pending payout: net and number of earnings in PENDING_PAYOUT. */
    interface PendingTotal {
        Long getOwnerId();

        /** Net of the earnings that can be paid out (those with an unresolved dispute are left out). */
        BigDecimal getNet();

        /** Number of earnings that can be paid out. */
        long getRows();

        /** Net held back because the booking has an unresolved dispute. */
        BigDecimal getDisputedNet();
    }

    Optional<OwnerEarning> findByBookingId(Long bookingId);

    /**
     * Row-locks the booking's earning (payment -> booking -> earning is the lock order of every money path), so a
     * refund and a payout cannot overwrite each other. Load it only after taking the lock: an instance already in the
     * persistence context would be returned as it was read.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from OwnerEarning e where e.booking.id = :bookingId")
    Optional<OwnerEarning> findByBookingIdForUpdate(@Param("bookingId") Long bookingId);

    /**
     * Owners with something to be paid out, the largest amount first. Earnings of bookings with an unresolved
     * (OPEN or UNDER_REVIEW) dispute are not payable: they are counted in {@code disputedNet} instead.
     */
    @Query("""
            select e.owner.id as ownerId,
                   sum(case when exists (select 1 from Dispute d where d.booking = e.booking
                                         and d.status <> com.smartparking.dispute.DisputeStatus.RESOLVED)
                            then 0 else e.net end) as net,
                   sum(case when exists (select 1 from Dispute d where d.booking = e.booking
                                         and d.status <> com.smartparking.dispute.DisputeStatus.RESOLVED)
                            then 0 else 1 end) as rows,
                   sum(case when exists (select 1 from Dispute d where d.booking = e.booking
                                         and d.status <> com.smartparking.dispute.DisputeStatus.RESOLVED)
                            then e.net else 0 end) as disputedNet
            from OwnerEarning e
            where e.status = com.smartparking.earning.EarningStatus.PENDING_PAYOUT
            group by e.owner.id
            having sum(case when exists (select 1 from Dispute d where d.booking = e.booking
                                         and d.status <> com.smartparking.dispute.DisputeStatus.RESOLVED)
                            then 0 else e.net end) > 0
            order by 2 desc, e.owner.id
            """)
    List<PendingTotal> pendingByOwner();

    /** Ids of those bookings that have an unresolved (OPEN or UNDER_REVIEW) dispute. */
    @Query("""
            select distinct d.booking.id from Dispute d
            where d.booking.id in :bookingIds and d.status <> com.smartparking.dispute.DisputeStatus.RESOLVED
            """)
    List<Long> disputedBookingIds(@Param("bookingIds") Collection<Long> bookingIds);

    @EntityGraph(attributePaths = {"booking", "booking.listing"})
    @Query("""
            select e from OwnerEarning e where e.owner.id = :ownerId
              and e.status = com.smartparking.earning.EarningStatus.PENDING_PAYOUT
            order by e.booking.startTime, e.id
            """)
    List<OwnerEarning> findPendingByOwnerId(@Param("ownerId") Long ownerId);

    /** Row-locks the earnings (in id order, so concurrent payouts cannot deadlock) and returns their current state. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from OwnerEarning e where e.id in :ids order by e.id")
    List<OwnerEarning> lockAllById(@Param("ids") Collection<Long> ids);

    List<OwnerEarning> findByBookingIdIn(Collection<Long> bookingIds);

    /** The owner's earnings added up per status (all time). */
    @Query("""
            select e.status as status, coalesce(sum(e.net), 0) as net, count(e) as rows
            from OwnerEarning e where e.owner.id = :ownerId group by e.status
            """)
    List<StatusTotal> totalsByStatus(@Param("ownerId") Long ownerId);

    /** Net and booking start of the owner's unreversed earnings whose booking starts in [from, to). */
    @Query("""
            select e.booking.startTime as startTime, e.net as net from OwnerEarning e
            where e.owner.id = :ownerId and e.status <> com.smartparking.earning.EarningStatus.REVERSED
              and e.booking.startTime >= :from and e.booking.startTime < :to
            """)
    List<DatedNet> findNetsStartingBetween(@Param("ownerId") Long ownerId, @Param("from") Instant from,
                                           @Param("to") Instant to);

    @Override
    @EntityGraph(attributePaths = {"booking", "booking.listing"})
    Page<OwnerEarning> findAll(Specification<OwnerEarning> spec, Pageable pageable);
}
