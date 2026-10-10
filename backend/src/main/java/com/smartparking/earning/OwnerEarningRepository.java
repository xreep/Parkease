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

        BigDecimal getNet();

        long getRows();
    }

    Optional<OwnerEarning> findByBookingId(Long bookingId);

    /** Owners with something to be paid out, the largest amount first. */
    @Query("""
            select e.owner.id as ownerId, sum(e.net) as net, count(e) as rows from OwnerEarning e
            where e.status = com.smartparking.earning.EarningStatus.PENDING_PAYOUT
            group by e.owner.id having sum(e.net) > 0
            order by sum(e.net) desc, e.owner.id
            """)
    List<PendingTotal> pendingByOwner();

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
