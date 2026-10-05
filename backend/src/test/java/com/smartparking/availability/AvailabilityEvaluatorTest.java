package com.smartparking.availability;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.availability.AvailabilityEvaluator.ListingAvailabilityInput;
import com.smartparking.availability.AvailabilityEvaluator.Result;
import com.smartparking.common.model.VehicleType;
import com.smartparking.pricing.TimeWindow;
import com.smartparking.slot.ParkingSlot;
import com.smartparking.slot.SlotSize;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AvailabilityEvaluatorTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC);
    private final AvailabilityEvaluator evaluator = new AvailabilityEvaluator();

    private long nextId = 1;

    /** Tue 2026-10-06 10:00-12:00 IST. */
    private TimeWindow window() {
        return TimeWindow.of(Instant.parse("2026-10-06T04:30:00Z"), Instant.parse("2026-10-06T06:30:00Z"), clock);
    }

    private ParkingSlot slot(VehicleType type, boolean active) {
        ParkingSlot s = new ParkingSlot();
        ReflectionTestUtils.setField(s, "id", nextId++);
        s.setLabel("S" + s.getId());
        s.setVehicleType(type);
        s.setSize(SlotSize.MEDIUM);
        s.setActive(active);
        return s;
    }

    private AvailabilityRule rule(int day, String open, String close) {
        AvailabilityRule r = new AvailabilityRule();
        r.setDayOfWeek(day);
        r.setOpenTime(LocalTime.parse(open));
        r.setCloseTime(LocalTime.parse(close));
        return r;
    }

    private List<AvailabilityRule> monToSat(String open, String close) {
        List<AvailabilityRule> rules = new ArrayList<>();
        for (int d = 1; d <= 6; d++) {
            rules.add(rule(d, open, close));
        }
        return rules;
    }

    private AvailabilityBlock block(ParkingSlot slot, String startUtc, String endUtc) {
        AvailabilityBlock b = new AvailabilityBlock();
        b.setSlot(slot);
        b.setStartTime(Instant.parse(startUtc));
        b.setEndTime(Instant.parse(endUtc));
        return b;
    }

    private ListingAvailabilityInput input(boolean open24x7, List<AvailabilityRule> rules, List<ParkingSlot> slots,
            List<AvailabilityBlock> blocks) {
        return new ListingAvailabilityInput(open24x7, rules, slots, blocks);
    }

    @Test
    void open24x7WithActiveSlotsIsAvailable() {
        var in = input(true, List.of(), List.of(slot(VehicleType.FOUR_WHEELER, true), slot(VehicleType.FOUR_WHEELER, true)),
                List.of());
        Result r = evaluator.evaluate(in, window(), VehicleType.FOUR_WHEELER);
        assertThat(r).isEqualTo(new Result(true, null, 2, 2));
    }

    @Test
    void isOpenWithinRuleHours() {
        assertThat(evaluator.isOpen(false, monToSat("08:00", "22:00"), window())).isTrue();
        assertThat(evaluator.isOpen(true, List.of(), window())).isTrue();
    }

    @Test
    void closedWhenWindowExceedsClosingTime() {
        // 21:00-23:00 IST = 15:30-17:30Z
        TimeWindow late = TimeWindow.of(Instant.parse("2026-10-06T15:30:00Z"), Instant.parse("2026-10-06T17:30:00Z"), clock);
        var in = input(false, monToSat("08:00", "22:00"), List.of(slot(VehicleType.FOUR_WHEELER, true)), List.of());
        assertThat(evaluator.evaluate(in, late, null)).isEqualTo(new Result(false, "CLOSED", 0, 1));
    }

    @Test
    void closedWhenNoRuleForThatDay() {
        var in = input(false, List.of(rule(7, "09:00", "21:00")), List.of(slot(VehicleType.FOUR_WHEELER, true)), List.of());
        assertThat(evaluator.evaluate(in, window(), null)).isEqualTo(new Result(false, "CLOSED", 0, 1));
    }

    @Test
    void closedBeforeOpeningTime() {
        // 06:00-08:00 IST = 00:30-02:30Z
        TimeWindow early = TimeWindow.of(Instant.parse("2026-10-06T00:30:00Z"), Instant.parse("2026-10-06T02:30:00Z"), clock);
        assertThat(evaluator.isOpen(false, monToSat("08:00", "22:00"), early)).isFalse();
    }

    @Test
    void multiDayWindowClosedUnlessOpen24x7() {
        TimeWindow multi = TimeWindow.of(Instant.parse("2026-10-06T04:30:00Z"), Instant.parse("2026-10-07T04:30:00Z"), clock);
        List<ParkingSlot> slots = List.of(slot(VehicleType.FOUR_WHEELER, true));
        assertThat(evaluator.evaluate(input(false, monToSat("00:00", "23:59"), slots, List.of()), multi, null).reason())
                .isEqualTo("CLOSED");
        assertThat(evaluator.evaluate(input(true, List.of(), slots, List.of()), multi, null).available()).isTrue();
    }

    @Test
    void wholeListingBlockOverlappingBlocks() {
        // 11:00-11:30 IST = 05:30-06:00Z
        var in = input(true, List.of(), List.of(slot(VehicleType.FOUR_WHEELER, true)),
                List.of(block(null, "2026-10-06T05:30:00Z", "2026-10-06T06:00:00Z")));
        assertThat(evaluator.evaluate(in, window(), null)).isEqualTo(new Result(false, "BLOCKED", 0, 1));
    }

    @Test
    void blockEndingExactlyAtWindowStartDoesNotOverlap() {
        var in = input(true, List.of(), List.of(slot(VehicleType.FOUR_WHEELER, true)),
                List.of(block(null, "2026-10-06T03:30:00Z", "2026-10-06T04:30:00Z")));
        assertThat(evaluator.evaluate(in, window(), null)).isEqualTo(new Result(true, null, 1, 1));
    }

    @Test
    void slotBlocksReduceFreeSlots() {
        ParkingSlot a = slot(VehicleType.FOUR_WHEELER, true);
        ParkingSlot b = slot(VehicleType.FOUR_WHEELER, true);
        var one = input(true, List.of(), List.of(a, b), List.of(block(a, "2026-10-06T05:00:00Z", "2026-10-06T07:00:00Z")));
        assertThat(evaluator.evaluate(one, window(), null)).isEqualTo(new Result(true, null, 1, 2));

        var both = input(true, List.of(), List.of(a, b), List.of(
                block(a, "2026-10-06T05:00:00Z", "2026-10-06T07:00:00Z"),
                block(b, "2026-10-06T04:00:00Z", "2026-10-06T05:30:00Z")));
        assertThat(evaluator.evaluate(both, window(), null)).isEqualTo(new Result(false, "FULLY_BOOKED", 0, 2));
    }

    @Test
    void noMatchingVehicleSlots() {
        var in = input(true, List.of(), List.of(slot(VehicleType.FOUR_WHEELER, true)), List.of());
        assertThat(evaluator.evaluate(in, window(), VehicleType.TWO_WHEELER)).isEqualTo(new Result(false, "NO_VEHICLE_SLOTS", 0, 0));
    }

    @Test
    void inactiveSlotsAreIgnored() {
        var in = input(true, List.of(), List.of(slot(VehicleType.FOUR_WHEELER, false), slot(VehicleType.FOUR_WHEELER, true)),
                List.of());
        assertThat(evaluator.evaluate(in, window(), null)).isEqualTo(new Result(true, null, 1, 1));
        var allInactive = input(true, List.of(), List.of(slot(VehicleType.FOUR_WHEELER, false)), List.of());
        assertThat(evaluator.evaluate(allInactive, window(), null).reason()).isEqualTo("NO_VEHICLE_SLOTS");
    }

    @Test
    void nullVehicleTypeCountsAllActiveSlots() {
        var in = input(true, List.of(), List.of(slot(VehicleType.TWO_WHEELER, true), slot(VehicleType.FOUR_WHEELER, true)),
                List.of());
        assertThat(evaluator.evaluate(in, window(), null)).isEqualTo(new Result(true, null, 2, 2));
        assertThat(evaluator.evaluate(in, window(), VehicleType.TWO_WHEELER)).isEqualTo(new Result(true, null, 1, 1));
    }

    @Test
    void windowExactlyMatchingOpeningHoursIsOpen() {
        // 08:00-22:00 IST on Tuesday = 02:30-16:30Z
        TimeWindow full = TimeWindow.of(Instant.parse("2026-10-06T02:30:00Z"), Instant.parse("2026-10-06T16:30:00Z"), clock);
        assertThat(evaluator.isOpen(false, monToSat("08:00", "22:00"), full)).isTrue();
    }

    @Test
    void noVehicleSlotsTakesPrecedenceOverClosed() {
        var in = input(false, List.of(), List.of(slot(VehicleType.FOUR_WHEELER, true)), List.of());
        assertThat(evaluator.evaluate(in, window(), VehicleType.TWO_WHEELER))
                .isEqualTo(new Result(false, "NO_VEHICLE_SLOTS", 0, 0));
    }

    @Test
    void closedTakesPrecedenceOverWholeListingBlock() {
        var in = input(false, List.of(), List.of(slot(VehicleType.FOUR_WHEELER, true)),
                List.of(block(null, "2026-10-06T05:30:00Z", "2026-10-06T06:00:00Z")));
        assertThat(evaluator.evaluate(in, window(), null)).isEqualTo(new Result(false, "CLOSED", 0, 1));
    }

    @Test
    void openingHoursUseTheIstDayNotTheUtcDay() {
        // 01:00-03:00 IST on Tuesday = Monday 19:30-21:30Z
        TimeWindow earlyTuesday = TimeWindow.of(Instant.parse("2026-10-05T19:30:00Z"), Instant.parse("2026-10-05T21:30:00Z"), clock);
        assertThat(evaluator.isOpen(false, List.of(rule(2, "00:00", "06:00")), earlyTuesday)).isTrue();
        assertThat(evaluator.isOpen(false, List.of(rule(1, "00:00", "06:00")), earlyTuesday)).isFalse();
    }
}
