package com.smartparking.admin.reports;

import static com.smartparking.owner.dashboard.DashboardRanges.dateOf;
import static com.smartparking.owner.dashboard.DashboardRanges.startOf;

import com.smartparking.admin.reports.AdminReportRepository.CityAggregate;
import com.smartparking.availability.AvailabilityRule;
import com.smartparking.availability.AvailabilityRuleRepository;
import com.smartparking.availability.SlotCoverage;
import com.smartparking.availability.SlotCoverage.OpenWindow;
import com.smartparking.availability.SlotCoverage.Span;
import com.smartparking.booking.BookingRepository.ListingSlotWindow;
import com.smartparking.listing.ParkingListing;
import com.smartparking.slot.ParkingSlot;
import com.smartparking.slot.ParkingSlotRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Slot utilization per city over APPROVED listings: booked slot-time over open slot-time (opening hours via
 * {@link SlotCoverage}) on the IST days {@code from..to}. Booked time is the union of the spans of the
 * CONFIRMED/ACTIVE/COMPLETED bookings created in that period, clipped to the open windows, like every other KPI is
 * attributed by booking creation day.
 */
@Component
@RequiredArgsConstructor
class PlatformUtilization {

    /** Approved listings and their active slots in a city, and open and booked slot-seconds. */
    record CityUsage(long listings, long slots, long openSeconds, long bookedSeconds) {

        CityUsage plus(CityUsage other) {
            return new CityUsage(listings + other.listings, slots + other.slots, openSeconds + other.openSeconds,
                    bookedSeconds + other.bookedSeconds);
        }

        BigDecimal bookedHours() {
            return BigDecimal.valueOf(bookedSeconds).divide(BigDecimal.valueOf(3600), 1, RoundingMode.HALF_UP);
        }

        BigDecimal utilizationPercent() {
            return percent(bookedSeconds, openSeconds);
        }
    }

    static final CityUsage NONE = new CityUsage(0, 0, 0, 0);

    private final AdminReportRepository reports;
    private final ParkingSlotRepository slots;
    private final AvailabilityRuleRepository rules;

    /** {@code part * 100 / whole} with one decimal (HALF_UP); 0.0 when there is nothing to divide by. */
    static BigDecimal percent(long part, long whole) {
        if (whole == 0) {
            return BigDecimal.ZERO.setScale(1);
        }
        return BigDecimal.valueOf(part * 100).divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }

    static long sum(List<CityAggregate> rows, java.util.function.ToLongFunction<CityAggregate> field) {
        return rows.stream().mapToLong(field).sum();
    }

    /** Usage per city id for the approved listings inside the state/city filter (0 = no filter). */
    Map<Long, CityUsage> byCity(LocalDate from, LocalDate to, long stateId, long cityId) {
        Instant rangeStart = startOf(from);
        Instant rangeEnd = startOf(to.plusDays(1));
        List<ParkingListing> approved = reports.approvedListings(stateId, cityId);
        if (approved.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = approved.stream().map(ParkingListing::getId).toList();
        Map<Long, List<ParkingSlot>> activeSlots = slots.findByListingIdInAndActiveTrue(ids).stream()
                .collect(Collectors.groupingBy(s -> s.getListing().getId()));
        Map<Long, Map<Integer, AvailabilityRule>> weekly = new HashMap<>();
        rules.findByListingIdIn(ids).forEach(r -> weekly
                .computeIfAbsent(r.getListing().getId(), k -> new HashMap<>()).putIfAbsent(r.getDayOfWeek(), r));

        // Booked spans bucketed by slot and IST day, so each (slot, day) only looks at the spans that touch it.
        Map<Long, Map<LocalDate, List<Span>>> bookedBySlotAndDay = new HashMap<>();
        for (ListingSlotWindow w : reports.bookedWindows(rangeStart, rangeEnd, rangeStart, rangeEnd, stateId, cityId)) {
            Span span = new Span(w.getStartTime().getEpochSecond(), w.getEndTime().getEpochSecond());
            LocalDate first = max(dateOf(w.getStartTime()), from);
            LocalDate last = min(dateOf(w.getEndTime().minusSeconds(1)), to);
            Map<LocalDate, List<Span>> perDay = bookedBySlotAndDay.computeIfAbsent(w.getSlotId(), k -> new HashMap<>());
            for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
                perDay.computeIfAbsent(d, k -> new ArrayList<>()).add(span);
            }
        }

        Map<Long, CityUsage> result = new HashMap<>();
        for (ParkingListing l : approved) {
            List<ParkingSlot> listingSlots = activeSlots.getOrDefault(l.getId(), List.of());
            long open = 0;
            long taken = 0;
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                AvailabilityRule rule = weekly.getOrDefault(l.getId(), Map.of()).get(d.getDayOfWeek().getValue());
                Optional<OpenWindow> window = SlotCoverage.openWindow(l.isOpen24x7(), rule, d);
                if (window.isEmpty()) {
                    continue;
                }
                Span span = window.get().span();
                open += span.length() * listingSlots.size();
                for (ParkingSlot s : listingSlots) {
                    taken += SlotCoverage.coveredLength(
                            bookedBySlotAndDay.getOrDefault(s.getId(), Map.of()).getOrDefault(d, List.of()), span);
                }
            }
            result.merge(l.getCity().getId(), new CityUsage(1, listingSlots.size(), open, taken), CityUsage::plus);
        }
        return result;
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
