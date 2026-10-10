package com.smartparking.owner.dashboard;

import static com.smartparking.owner.dashboard.DashboardRanges.dateOf;
import static com.smartparking.owner.dashboard.DashboardRanges.startOf;

import com.smartparking.availability.AvailabilityRuleRepository;
import com.smartparking.booking.BookingRepository;
import com.smartparking.booking.BookingRepository.StartAndStatus;
import com.smartparking.booking.BookingStatus;
import com.smartparking.booking.OwnerBookingService;
import com.smartparking.earning.EarningStatus;
import com.smartparking.earning.OwnerEarningRepository;
import com.smartparking.earning.OwnerEarningRepository.DatedNet;
import com.smartparking.earning.OwnerEarningRepository.StatusTotal;
import com.smartparking.listing.ListingStatus;
import com.smartparking.listing.ParkingListing;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.owner.dashboard.SlotOccupancy.Usage;
import com.smartparking.owner.dashboard.dto.OwnerStatsDto;
import com.smartparking.payment.PaymentStatus;
import com.smartparking.review.ReviewRepository;
import com.smartparking.review.ReviewRepository.RatingTotals;
import com.smartparking.slot.ParkingSlotRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The owner dashboard numbers. Bookings and earnings are attributed to the IST date their booking starts on;
 * reversed earnings count as zero. Occupancy is booked slot-time over open slot-time of the owner's APPROVED listings.
 */
@Service
@RequiredArgsConstructor
public class OwnerStatsService {

    static final int MAX_DAYS = 366;
    static final int DEFAULT_DAYS = 30;
    static final int UPCOMING = 5;

    private static final Set<BookingStatus> COUNTED = Set.of(BookingStatus.CONFIRMED, BookingStatus.ACTIVE,
            BookingStatus.COMPLETED);

    private final BookingRepository bookings;
    private final OwnerEarningRepository earnings;
    private final ParkingListingRepository listings;
    private final ParkingSlotRepository slots;
    private final AvailabilityRuleRepository rules;
    private final ReviewRepository reviews;
    private final OwnerBookingService ownerBookings;
    private final Clock clock;

    @Transactional(readOnly = true)
    public OwnerStatsDto stats(Long ownerId, LocalDate fromParam, LocalDate toParam) {
        LocalDate today = dateOf(clock.instant());
        LocalDate to = toParam != null ? toParam : today;
        LocalDate from = fromParam != null ? fromParam : to.minusDays(DEFAULT_DAYS - 1L);
        DashboardRanges.validate(from, to, MAX_DAYS);
        Instant rangeStart = startOf(from);
        Instant rangeEnd = startOf(to.plusDays(1));

        // Per-day series, keyed by IST date (a TreeMap keeps the days in order).
        Map<LocalDate, BigDecimal> dayEarnings = new TreeMap<>();
        Map<LocalDate, Integer> dayBookings = new HashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            dayEarnings.put(d, money(BigDecimal.ZERO));
            dayBookings.put(d, 0);
        }
        BigDecimal earned = BigDecimal.ZERO;
        for (DatedNet e : earnings.findNetsStartingBetween(ownerId, rangeStart, rangeEnd)) {
            earned = earned.add(e.getNet());
            dayEarnings.merge(dateOf(e.getStartTime()), e.getNet(), BigDecimal::add);
        }
        int booked = 0;
        for (StartAndStatus b : bookings.findStartsInRange(ownerId, COUNTED, rangeStart, rangeEnd)) {
            booked++;
            dayBookings.merge(dateOf(b.getStartTime()), 1, Integer::sum);
        }
        int cancelled = (int) bookings.countPaidCancellations(ownerId, rangeStart, rangeEnd, PaymentStatus.MONEY_MOVED);

        List<ParkingListing> owned = listings.findByOwnerId(ownerId);
        List<OwnerStatsDto.DayPoint> series = dayEarnings.entrySet().stream()
                .map(e -> new OwnerStatsDto.DayPoint(e.getKey(), money(e.getValue()), dayBookings.get(e.getKey())))
                .toList();
        Ratings ratings = ratings(ownerId);
        return new OwnerStatsDto(from, to,
                new OwnerStatsDto.Totals(money(earned), booked, cancelled, occupancy(ownerId, owned, from, to),
                        ratings.average(), ratings.count()),
                balances(ownerId),
                (int) bookings.countByListingOwnerIdAndStatus(ownerId, BookingStatus.AWAITING_APPROVAL),
                ownerBookings.nextUpcoming(ownerId, UPCOMING), series);
    }

    private OwnerStatsDto.Balances balances(Long ownerId) {
        Map<EarningStatus, BigDecimal> byStatus = new EnumMap<>(EarningStatus.class);
        for (StatusTotal t : earnings.totalsByStatus(ownerId)) {
            byStatus.put(t.getStatus(), t.getNet());
        }
        return new OwnerStatsDto.Balances(money(byStatus.getOrDefault(EarningStatus.HELD, BigDecimal.ZERO)),
                money(byStatus.getOrDefault(EarningStatus.PENDING_PAYOUT, BigDecimal.ZERO)),
                money(byStatus.getOrDefault(EarningStatus.PAID, BigDecimal.ZERO)));
    }

    private record Ratings(BigDecimal average, int count) {
    }

    /** Average (one decimal, HALF_UP) and number of all reviews of the owner's listings, straight from the reviews. */
    private Ratings ratings(Long ownerId) {
        RatingTotals totals = reviews.totalsForOwner(ownerId);
        long count = totals.getRatingCount();
        BigDecimal average = count == 0 ? BigDecimal.ZERO.setScale(1)
                : BigDecimal.valueOf(totals.getRatingSum()).divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP);
        return new Ratings(average, (int) count);
    }

    /** Booked slot-time as a share of open slot-time of the owner's approved listings, one decimal. */
    private BigDecimal occupancy(Long ownerId, List<ParkingListing> owned, LocalDate from, LocalDate to) {
        List<ParkingListing> approved = owned.stream().filter(l -> l.getStatus() == ListingStatus.APPROVED).toList();
        if (approved.isEmpty()) {
            return BigDecimal.ZERO.setScale(1);
        }
        List<Long> ids = approved.stream().map(ParkingListing::getId).toList();
        Usage usage = SlotOccupancy.perListing(approved, slots.findByListingIdInAndActiveTrue(ids),
                        rules.findByListingIdIn(ids), bookings.findBookedWindows(ownerId, startOf(from),
                                startOf(to.plusDays(1))), from, to)
                .values().stream().reduce(Usage.NONE, Usage::plus);
        if (usage.openSeconds() == 0) {
            return BigDecimal.ZERO.setScale(1);
        }
        return BigDecimal.valueOf(usage.bookedSeconds() * 100)
                .divide(BigDecimal.valueOf(usage.openSeconds()), 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
