package com.smartparking.availability;

import com.smartparking.common.model.VehicleType;
import com.smartparking.pricing.TimeWindow;
import com.smartparking.slot.ParkingSlot;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Pure availability logic: opening hours, blocks and slot capacity for a time window. */
@Component
public class AvailabilityEvaluator {

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    public record ListingAvailabilityInput(
            boolean open24x7,
            List<AvailabilityRule> rules,
            List<ParkingSlot> slots,
            List<AvailabilityBlock> blocks,
            Set<Long> bookedSlotIds) {

        /** Input without live bookings. */
        public ListingAvailabilityInput(boolean open24x7, List<AvailabilityRule> rules, List<ParkingSlot> slots,
                List<AvailabilityBlock> blocks) {
            this(open24x7, rules, slots, blocks, Set.of());
        }
    }

    /** {@code freeSlotIds}: free candidate slots (not blocked, not booked), ordered by slot label. */
    public record Result(boolean available, String reason, int freeSlots, int totalSlots, List<Long> freeSlotIds) {

        public Result(boolean available, String reason, int freeSlots, int totalSlots) {
            this(available, reason, freeSlots, totalSlots, List.of());
        }
    }

    private static final LocalTime LAST_MINUTE = LocalTime.of(23, 59);

    /**
     * Opening hours are single-day: the whole window must fit inside one day's rule (in IST).
     * A window ending exactly at midnight of the next day counts as ending at the end of the start day,
     * which requires that day's rule to run until 23:59.
     */
    public boolean isOpen(boolean open24x7, List<AvailabilityRule> rules, TimeWindow w) {
        if (open24x7) {
            return true;
        }
        ZonedDateTime start = w.start().atZone(ZONE);
        ZonedDateTime end = w.end().atZone(ZONE);
        int day = start.getDayOfWeek().getValue();
        boolean endsAtMidnight = end.toLocalTime().equals(LocalTime.MIDNIGHT)
                && end.toLocalDate().equals(start.toLocalDate().plusDays(1));
        if (endsAtMidnight) {
            return rules.stream()
                    .filter(r -> r.getDayOfWeek() == day)
                    .anyMatch(r -> !start.toLocalTime().isBefore(r.getOpenTime())
                            && !r.getCloseTime().isBefore(LAST_MINUTE));
        }
        if (!end.toLocalDate().equals(start.toLocalDate())) {
            return false;
        }
        return rules.stream()
                .filter(r -> r.getDayOfWeek() == day)
                .anyMatch(r -> !start.toLocalTime().isBefore(r.getOpenTime())
                        && !end.toLocalTime().isAfter(r.getCloseTime()));
    }

    public Result evaluate(ListingAvailabilityInput in, TimeWindow w, VehicleType vehicleType) {
        List<ParkingSlot> candidates = in.slots().stream()
                .filter(ParkingSlot::isActive)
                .filter(s -> vehicleType == null || s.getVehicleType() == vehicleType)
                .toList();
        int total = candidates.size();
        if (total == 0) {
            return new Result(false, "NO_VEHICLE_SLOTS", 0, 0);
        }
        if (!isOpen(in.open24x7(), in.rules(), w)) {
            return new Result(false, "CLOSED", 0, total);
        }
        boolean listingBlocked = in.blocks().stream()
                .anyMatch(b -> b.getSlot() == null && w.overlaps(b.getStartTime(), b.getEndTime()));
        if (listingBlocked) {
            return new Result(false, "BLOCKED", 0, total);
        }
        // Compare by id: block.getSlot() may be a lazy proxy rather than the same instance.
        Set<Long> blockedSlotIds = in.blocks().stream()
                .filter(b -> b.getSlot() != null && w.overlaps(b.getStartTime(), b.getEndTime()))
                .map(b -> b.getSlot().getId())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> booked = in.bookedSlotIds() == null ? Set.of() : in.bookedSlotIds();
        List<Long> freeIds = candidates.stream()
                .filter(s -> !blockedSlotIds.contains(s.getId()) && !booked.contains(s.getId()))
                .sorted(Comparator.comparing(ParkingSlot::getLabel))
                .map(ParkingSlot::getId)
                .toList();
        if (freeIds.isEmpty()) {
            return new Result(false, "FULLY_BOOKED", 0, total);
        }
        return new Result(true, null, freeIds.size(), total, freeIds);
    }
}
