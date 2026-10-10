package com.smartparking.owner.dashboard;

import static com.smartparking.owner.dashboard.DashboardRanges.dateOf;

import com.smartparking.availability.AvailabilityRule;
import com.smartparking.availability.SlotCoverage;
import com.smartparking.availability.SlotCoverage.OpenWindow;
import com.smartparking.availability.SlotCoverage.Span;
import com.smartparking.booking.BookingRepository.ListingSlotWindow;
import com.smartparking.listing.ParkingListing;
import com.smartparking.slot.ParkingSlot;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Open and booked slot-time of listings over a range of IST days, shared by the owner dashboard's occupancy and the
 * admin utilization: each listing is open in the windows of its weekly rules (or around the clock) on every active slot,
 * and a slot is booked where the union of its booking spans (clipped to those windows) lies.
 */
public final class SlotOccupancy {

    /** Open and booked slot-seconds. */
    public record Usage(long openSeconds, long bookedSeconds) {

        public static final Usage NONE = new Usage(0, 0);

        public Usage plus(Usage other) {
            return new Usage(openSeconds + other.openSeconds, bookedSeconds + other.bookedSeconds);
        }
    }

    private SlotOccupancy() {
    }

    /**
     * Usage per listing id for {@code listings} on the days {@code from..to}. {@code slots} are the listings' active
     * slots, {@code rules} their weekly opening rules and {@code booked} the spans of the bookings that count as
     * booked time and overlap the range (any slot or listing outside the given lists is ignored).
     */
    public static Map<Long, Usage> perListing(List<ParkingListing> listings, List<ParkingSlot> slots,
                                              List<AvailabilityRule> rules, List<ListingSlotWindow> booked,
                                              LocalDate from, LocalDate to) {
        Map<Long, List<ParkingSlot>> slotsByListing = slots.stream()
                .collect(Collectors.groupingBy(s -> s.getListing().getId()));
        Map<Long, Map<Integer, AvailabilityRule>> weekly = new HashMap<>();
        rules.forEach(r -> weekly.computeIfAbsent(r.getListing().getId(), k -> new HashMap<>())
                .putIfAbsent(r.getDayOfWeek(), r));
        // Booked spans bucketed by slot and IST day, so each (slot, day) only looks at the spans that touch it.
        Map<Long, Map<LocalDate, List<Span>>> bookedBySlotAndDay = new HashMap<>();
        for (ListingSlotWindow w : booked) {
            Span span = new Span(w.getStartTime().getEpochSecond(), w.getEndTime().getEpochSecond());
            LocalDate first = max(dateOf(w.getStartTime()), from);
            LocalDate last = min(dateOf(w.getEndTime().minusSeconds(1)), to);
            Map<LocalDate, List<Span>> perDay = bookedBySlotAndDay.computeIfAbsent(w.getSlotId(), k -> new HashMap<>());
            for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
                perDay.computeIfAbsent(d, k -> new ArrayList<>()).add(span);
            }
        }

        Map<Long, Usage> result = new HashMap<>();
        for (ParkingListing l : listings) {
            List<ParkingSlot> listingSlots = slotsByListing.getOrDefault(l.getId(), List.of());
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
            result.put(l.getId(), new Usage(open, taken));
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
