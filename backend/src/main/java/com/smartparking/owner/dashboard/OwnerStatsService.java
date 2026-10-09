package com.smartparking.owner.dashboard;

import static com.smartparking.owner.dashboard.DashboardRanges.dateOf;
import static com.smartparking.owner.dashboard.DashboardRanges.startOf;

import com.smartparking.availability.AvailabilityRule;
import com.smartparking.availability.AvailabilityRuleRepository;
import com.smartparking.availability.SlotCoverage;
import com.smartparking.availability.SlotCoverage.OpenWindow;
import com.smartparking.availability.SlotCoverage.Span;
import com.smartparking.booking.BookingRepository;
import com.smartparking.booking.BookingRepository.ListingSlotWindow;
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
import com.smartparking.owner.dashboard.dto.OwnerStatsDto;
import com.smartparking.slot.ParkingSlot;
import com.smartparking.slot.ParkingSlotRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
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
    private static final Set<BookingStatus> CANCELLED = Set.of(BookingStatus.CANCELLED, BookingStatus.REJECTED);

    private final BookingRepository bookings;
    private final OwnerEarningRepository earnings;
    private final ParkingListingRepository listings;
    private final ParkingSlotRepository slots;
    private final AvailabilityRuleRepository rules;
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
        int cancelled = 0;
        List<BookingStatus> statuses = new ArrayList<>(COUNTED);
        statuses.addAll(CANCELLED);
        for (StartAndStatus b : bookings.findStartsInRange(ownerId, statuses, rangeStart, rangeEnd)) {
            if (COUNTED.contains(b.getStatus())) {
                booked++;
                dayBookings.merge(dateOf(b.getStartTime()), 1, Integer::sum);
            } else {
                cancelled++;
            }
        }

        List<ParkingListing> owned = listings.findByOwnerId(ownerId);
        List<OwnerStatsDto.DayPoint> series = dayEarnings.entrySet().stream()
                .map(e -> new OwnerStatsDto.DayPoint(e.getKey(), money(e.getValue()), dayBookings.get(e.getKey())))
                .toList();
        return new OwnerStatsDto(from, to,
                new OwnerStatsDto.Totals(money(earned), booked, cancelled, occupancy(ownerId, owned, from, to),
                        weightedRating(owned), owned.stream().mapToInt(ParkingListing::getReviewCount).sum()),
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

    /** Average of the listings' ratings weighted by their review counts; zero without reviews. */
    private static BigDecimal weightedRating(List<ParkingListing> owned) {
        BigDecimal sum = BigDecimal.ZERO;
        long count = 0;
        for (ParkingListing l : owned) {
            sum = sum.add(l.getAvgRating().multiply(BigDecimal.valueOf(l.getReviewCount())));
            count += l.getReviewCount();
        }
        return count == 0 ? BigDecimal.ZERO.setScale(1)
                : sum.divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP);
    }

    /** Booked slot-time as a share of open slot-time of the owner's approved listings, one decimal. */
    private BigDecimal occupancy(Long ownerId, List<ParkingListing> owned, LocalDate from, LocalDate to) {
        List<ParkingListing> approved = owned.stream().filter(l -> l.getStatus() == ListingStatus.APPROVED).toList();
        if (approved.isEmpty()) {
            return BigDecimal.ZERO.setScale(1);
        }
        List<Long> ids = approved.stream().map(ParkingListing::getId).toList();
        Map<Long, List<ParkingSlot>> activeSlots = slots.findByListingIdInAndActiveTrue(ids).stream()
                .collect(Collectors.groupingBy(s -> s.getListing().getId()));
        Map<Long, Map<Integer, AvailabilityRule>> weekly = new HashMap<>();
        rules.findByListingIdIn(ids).forEach(r -> weekly
                .computeIfAbsent(r.getListing().getId(), k -> new HashMap<>()).putIfAbsent(r.getDayOfWeek(), r));
        Map<Long, List<Span>> bookedBySlot = new HashMap<>();
        for (ListingSlotWindow w : bookings.findBookedWindows(ownerId, startOf(from), startOf(to.plusDays(1)))) {
            bookedBySlot.computeIfAbsent(w.getSlotId(), k -> new ArrayList<>())
                    .add(new Span(w.getStartTime().getEpochSecond(), w.getEndTime().getEpochSecond()));
        }

        long open = 0;
        long taken = 0;
        for (ParkingListing l : approved) {
            List<ParkingSlot> listingSlots = activeSlots.getOrDefault(l.getId(), List.of());
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                AvailabilityRule rule = weekly.getOrDefault(l.getId(), Map.of()).get(d.getDayOfWeek().getValue());
                Optional<OpenWindow> window = SlotCoverage.openWindow(l.isOpen24x7(), rule, d);
                if (window.isEmpty()) {
                    continue;
                }
                Span span = window.get().span();
                open += span.length() * listingSlots.size();
                for (ParkingSlot s : listingSlots) {
                    taken += SlotCoverage.coveredLength(bookedBySlot.getOrDefault(s.getId(), List.of()), span);
                }
            }
        }
        if (open == 0) {
            return BigDecimal.ZERO.setScale(1);
        }
        return BigDecimal.valueOf(taken * 100).divide(BigDecimal.valueOf(open), 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
