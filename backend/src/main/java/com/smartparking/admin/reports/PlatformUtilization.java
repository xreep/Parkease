package com.smartparking.admin.reports;

import static com.smartparking.owner.dashboard.DashboardRanges.startOf;

import com.smartparking.availability.AvailabilityRuleRepository;
import com.smartparking.listing.ParkingListing;
import com.smartparking.owner.dashboard.SlotOccupancy;
import com.smartparking.owner.dashboard.SlotOccupancy.Usage;
import com.smartparking.slot.ParkingSlot;
import com.smartparking.slot.ParkingSlotRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Slot utilization per city over APPROVED listings (spec §8): booked slot-time over available slot-time in the
 * period, i.e. the CONFIRMED/ACTIVE/COMPLETED bookings that overlap the IST days {@code from..to}, whenever they were
 * created, clipped to the listings' open windows ({@link SlotOccupancy}, shared with the owner dashboard).
 */
@Component
@RequiredArgsConstructor
class PlatformUtilization {

    /** Approved listings and their active slots in a city, and their open and booked slot-time. */
    record CityUsage(long listings, long slots, Usage time) {

        static final CityUsage NONE = new CityUsage(0, 0, Usage.NONE);

        CityUsage plus(CityUsage other) {
            return new CityUsage(listings + other.listings, slots + other.slots, time.plus(other.time));
        }

        BigDecimal bookedHours() {
            return BigDecimal.valueOf(time.bookedSeconds()).divide(BigDecimal.valueOf(3600), 1, RoundingMode.HALF_UP);
        }

        BigDecimal utilizationPercent() {
            return ReportMath.percent(time.bookedSeconds(), time.openSeconds());
        }
    }

    private final AdminReportRepository reports;
    private final ParkingSlotRepository slots;
    private final AvailabilityRuleRepository rules;

    /** Usage per city id for the approved listings inside the state/city filter (0 = no filter). */
    Map<Long, CityUsage> byCity(LocalDate from, LocalDate to, long stateId, long cityId) {
        List<ParkingListing> approved = reports.approvedListings(stateId, cityId);
        if (approved.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = approved.stream().map(ParkingListing::getId).toList();
        List<ParkingSlot> activeSlots = slots.findByListingIdInAndActiveTrue(ids);
        Map<Long, Long> slotCounts = activeSlots.stream()
                .collect(Collectors.groupingBy(s -> s.getListing().getId(), Collectors.counting()));
        Map<Long, Usage> time = SlotOccupancy.perListing(approved, activeSlots, rules.findByListingIdIn(ids),
                reports.bookedWindows(startOf(from), startOf(to.plusDays(1)), stateId, cityId), from, to);

        Map<Long, CityUsage> result = new HashMap<>();
        for (ParkingListing l : approved) {
            result.merge(l.getCity().getId(), new CityUsage(1, slotCounts.getOrDefault(l.getId(), 0L),
                    time.getOrDefault(l.getId(), Usage.NONE)), CityUsage::plus);
        }
        return result;
    }
}
