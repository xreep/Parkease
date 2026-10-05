package com.smartparking.availability;

import com.smartparking.common.model.VehicleType;
import com.smartparking.pricing.TimeWindow;
import com.smartparking.slot.ParkingSlot;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
            List<AvailabilityBlock> blocks) {
    }

    public record Result(boolean available, String reason, int freeSlots, int totalSlots) {
    }

    /** Opening hours are single-day: the whole window must fit inside one day's rule (in IST). */
    public boolean isOpen(boolean open24x7, List<AvailabilityRule> rules, TimeWindow w) {
        if (open24x7) {
            return true;
        }
        ZonedDateTime start = w.start().atZone(ZONE);
        ZonedDateTime end = w.end().atZone(ZONE);
        if (!end.toLocalDate().equals(start.toLocalDate())) {
            return false;
        }
        int day = start.getDayOfWeek().getValue();
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
        int free = (int) candidates.stream().filter(s -> !blockedSlotIds.contains(s.getId())).count();
        if (free == 0) {
            return new Result(false, "FULLY_BOOKED", 0, total);
        }
        return new Result(true, null, free, total);
    }
}
