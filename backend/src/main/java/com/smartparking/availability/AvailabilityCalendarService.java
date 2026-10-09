package com.smartparking.availability;

import com.smartparking.availability.SlotCoverage.OpenWindow;
import com.smartparking.availability.SlotCoverage.Span;
import com.smartparking.availability.dto.AvailabilityCalendarDto;
import com.smartparking.availability.dto.DayAvailabilityDto;
import com.smartparking.booking.BookingRepository;
import com.smartparking.booking.BookingRepository.SlotWindow;
import com.smartparking.common.error.ApiException;
import com.smartparking.listing.ListingStatus;
import com.smartparking.listing.ParkingListing;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.slot.ParkingSlot;
import com.smartparking.slot.ParkingSlotRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The public, indicative day-level availability of an approved listing: per IST day, how much of the open slot-time is
 * taken by live bookings, blocks and (today) time that has already passed. One query each for bookings and blocks over
 * the whole range; the rest is computed in memory. The quote at booking time stays authoritative.
 */
@Service
@RequiredArgsConstructor
public class AvailabilityCalendarService {

    static final int MAX_DAYS = 31;
    static final int MAX_AHEAD_DAYS = 90;
    static final int LIMITED_FROM_PERCENT = 60;
    static final int FULL_FROM_PERCENT = 98;

    private final ParkingListingRepository listings;
    private final ParkingSlotRepository slots;
    private final AvailabilityRuleRepository rules;
    private final AvailabilityBlockRepository blocks;
    private final BookingRepository bookings;
    private final Clock clock;

    @Transactional(readOnly = true)
    public AvailabilityCalendarDto calendar(Long listingId, LocalDate from, LocalDate to) {
        Instant now = clock.instant();
        LocalDate today = now.atZone(AvailabilityEvaluator.ZONE).toLocalDate();
        validate(from, to);
        // Only today up to 90 days ahead can be booked: the requested range is clamped to that window.
        LocalDate maxDay = today.plusDays(MAX_AHEAD_DAYS);
        from = from.isBefore(today) ? today : from;
        to = to.isAfter(maxDay) ? maxDay : to;
        if (from.isAfter(to)) {
            throw invalid("The range lies outside the bookable window (today to " + MAX_AHEAD_DAYS + " days ahead)");
        }
        ParkingListing listing = listings.findByIdAndStatus(listingId, ListingStatus.APPROVED)
                .orElseThrow(() -> ApiException.notFound("Listing not found"));

        Instant rangeStart = startOf(from);
        Instant rangeEnd = startOf(to.plusDays(1));
        List<ParkingSlot> active = slots.findByListingIdOrderByLabelAsc(listingId).stream()
                .filter(ParkingSlot::isActive).toList();
        Map<Integer, AvailabilityRule> ruleByDay = new HashMap<>();
        if (!listing.isOpen24x7()) {
            rules.findByListingIdOrderByDayOfWeekAsc(listingId).forEach(r -> ruleByDay.putIfAbsent(r.getDayOfWeek(), r));
        }

        // Busy spans per slot id (booked or blocked) and listing-wide blocks, read once for the whole range.
        Map<Long, List<Span>> busy = new HashMap<>();
        List<Span> listingWide = new ArrayList<>();
        for (SlotWindow w : bookings.findLiveWindows(listingId, rangeStart, rangeEnd, now)) {
            busy.computeIfAbsent(w.getSlotId(), k -> new ArrayList<>()).add(span(w.getStartTime(), w.getEndTime()));
        }
        for (AvailabilityBlock b : blocks.findOverlapping(List.of(listingId), rangeStart, rangeEnd)) {
            Span span = span(b.getStartTime(), b.getEndTime());
            if (b.getSlot() == null) {
                listingWide.add(span);
            } else {
                busy.computeIfAbsent(b.getSlot().getId(), k -> new ArrayList<>()).add(span);
            }
        }

        List<DayAvailabilityDto> days = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            days.add(day(date, listing.isOpen24x7(), ruleByDay.get(date.getDayOfWeek().getValue()), active, busy,
                    listingWide, now.getEpochSecond()));
        }
        return new AvailabilityCalendarDto(listingId, days);
    }

    private DayAvailabilityDto day(LocalDate date, boolean open24x7, AvailabilityRule rule, List<ParkingSlot> active,
                                   Map<Long, List<Span>> busy, List<Span> listingWide, long nowSecond) {
        Optional<OpenWindow> open = SlotCoverage.openWindow(open24x7, rule, date);
        if (open.isEmpty()) {
            return new DayAvailabilityDto(date, AvailabilityLevel.CLOSED, null, null, 0);
        }
        Span window = open.get().span();
        long openSeconds = window.length() * active.size();
        int percent = 100;
        if (openSeconds > 0) {
            long taken = 0;
            for (ParkingSlot slot : active) {
                List<Span> spans = new ArrayList<>(listingWide);
                spans.addAll(busy.getOrDefault(slot.getId(), List.of()));
                spans.add(new Span(Long.MIN_VALUE, nowSecond)); // time that has passed cannot be booked
                taken += SlotCoverage.coveredLength(spans, window);
            }
            percent = (int) (taken * 100 / openSeconds);
        }
        AvailabilityLevel level = percent >= FULL_FROM_PERCENT ? AvailabilityLevel.FULL
                : percent >= LIMITED_FROM_PERCENT ? AvailabilityLevel.LIMITED : AvailabilityLevel.AVAILABLE;
        return new DayAvailabilityDto(date, level, open.get().openLabel(), open.get().closeLabel(), percent);
    }

    private static Span span(Instant start, Instant end) {
        return new Span(start.getEpochSecond(), end.getEpochSecond());
    }

    private static Instant startOf(LocalDate date) {
        return date.atStartOfDay(AvailabilityEvaluator.ZONE).toInstant();
    }

    /** The requested range must be complete, not reversed and span at most {@value #MAX_DAYS} days. */
    private static void validate(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw invalid("from and to are required");
        }
        if (to.isBefore(from)) {
            throw invalid("from must not be after to");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw invalid("The range can span at most " + MAX_DAYS + " days");
        }
    }

    private static ApiException invalid(String message) {
        return ApiException.badRequest("INVALID_DATE_RANGE", message);
    }
}
