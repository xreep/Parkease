package com.smartparking.admin.reports;

import com.smartparking.booking.Booking;
import com.smartparking.booking.BookingRepository.ListingSlotWindow;
import com.smartparking.listing.ParkingListing;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Platform-wide aggregates for the admin KPIs and reports. Money and booking counts are attributed by the
 * booking's creation instant (callers pass the IST day boundaries); booked time is by period. {@code stateId}/
 * {@code cityId} 0 match everything. "Money moved" is the
 * payment statuses of {@code PaymentStatus.MONEY_MOVED}; platform fee and GST count only for payments that kept
 * their money (captured or partially refunded, not fully refunded).
 */
public interface AdminReportRepository extends Repository<Booking, Long> {

    /** One city's bookings created in the range, summed. */
    interface CityAggregate {
        Long getCityId();

        long getBookings();

        long getConfirmed();

        long getCancelled();

        BigDecimal getGmv();

        BigDecimal getPlatformFees();

        BigDecimal getGst();

        BigDecimal getRefunds();

        BigDecimal getOwnerEarnings();
    }

    /** One IST day's bookings created, with GMV and platform revenue. */
    interface DayAggregate {
        String getDay();

        long getBookings();

        BigDecimal getGmv();

        BigDecimal getRevenue();
    }

    /**
     * confirmed = ever reached CONFIRMED (a confirmation time, or a status at or beyond it); cancelled = CANCELLED or
     * REJECTED after money moved (an abandoned unpaid hold is no cancellation); gmv = total less refunds of
     * money-moved bookings; owner earnings exclude REVERSED ones.
     */
    @Query(nativeQuery = true, value = """
            select l.city_id as "cityId",
                   count(*) as "bookings",
                   count(*) filter (where b.confirmed_at is not null
                                       or b.status in ('CONFIRMED', 'ACTIVE', 'COMPLETED')) as "confirmed",
                   count(*) filter (where b.status in ('CANCELLED', 'REJECTED') and p.status in (:moved)) as "cancelled",
                   coalesce(sum(b.total_amount - b.refund_amount) filter (where p.status in (:moved)), 0) as "gmv",
                   coalesce(sum(b.platform_fee) filter (where p.status in (:feeBearing)), 0) as "platformFees",
                   coalesce(sum(b.gst_amount) filter (where p.status in (:feeBearing)), 0) as "gst",
                   coalesce(sum(b.refund_amount), 0) as "refunds",
                   coalesce(sum(e.net) filter (where e.status <> 'REVERSED'), 0) as "ownerEarnings"
            from bookings b
              join parking_listings l on l.id = b.listing_id
              join cities c on c.id = l.city_id
              left join payments p on p.booking_id = b.id
              left join owner_earnings e on e.booking_id = b.id
            where b.created_at >= :from and b.created_at < :to
              and (:stateId = 0 or c.state_id = :stateId) and (:cityId = 0 or c.id = :cityId)
            group by l.city_id
            """)
    List<CityAggregate> byCity(@Param("from") Instant from, @Param("to") Instant to,
                               @Param("stateId") long stateId, @Param("cityId") long cityId,
                               @Param("moved") Collection<String> moved,
                               @Param("feeBearing") Collection<String> feeBearing);

    @Query(nativeQuery = true, value = """
            select to_char(b.created_at at time zone 'Asia/Kolkata', 'YYYY-MM-DD') as "day",
                   count(*) as "bookings",
                   coalesce(sum(b.total_amount - b.refund_amount) filter (where p.status in (:moved)), 0) as "gmv",
                   coalesce(sum(b.platform_fee) filter (where p.status in (:feeBearing)), 0) as "revenue"
            from bookings b
              left join payments p on p.booking_id = b.id
            where b.created_at >= :from and b.created_at < :to
            group by 1
            """)
    List<DayAggregate> byDay(@Param("from") Instant from, @Param("to") Instant to,
                             @Param("moved") Collection<String> moved,
                             @Param("feeBearing") Collection<String> feeBearing);

    /** APPROVED listings (with their city) inside the state/city filter. */
    @Query("""
            select l from ParkingListing l join fetch l.city c
            where l.status = com.smartparking.listing.ListingStatus.APPROVED
              and (:stateId = 0L or c.state.id = :stateId) and (:cityId = 0L or c.id = :cityId)
            """)
    List<ParkingListing> approvedListings(@Param("stateId") long stateId, @Param("cityId") long cityId);

    /**
     * Spans of the CONFIRMED, ACTIVE and COMPLETED bookings on APPROVED listings inside the filter that overlap
     * [from, to), whenever they were created: booked time is by period.
     */
    @Query("""
            select b.listing.id as listingId, b.slot.id as slotId, b.startTime as startTime, b.endTime as endTime
            from Booking b
            where b.listing.status = com.smartparking.listing.ListingStatus.APPROVED
              and b.status in (com.smartparking.booking.BookingStatus.CONFIRMED,
                               com.smartparking.booking.BookingStatus.ACTIVE,
                               com.smartparking.booking.BookingStatus.COMPLETED)
              and b.startTime < :to and b.endTime > :from
              and (:stateId = 0L or b.listing.city.state.id = :stateId)
              and (:cityId = 0L or b.listing.city.id = :cityId)
            """)
    List<ListingSlotWindow> bookedWindows(@Param("from") Instant from, @Param("to") Instant to,
                                          @Param("stateId") long stateId, @Param("cityId") long cityId);
}
