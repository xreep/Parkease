package com.smartparking.availability;

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
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private static final long DAY_SECONDS = 24 * 3600L;

    private final ParkingListingRepository listings;
    private final ParkingSlotRepository slots;
    private final AvailabilityRuleRepository rules;
    private final AvailabilityBlockRepository blocks;
    private final BookingRepository bookings;
    private final Clock clock;

    /** A half-open span of epoch seconds. */
    private record Span(long start, long end) {
    }

    @Transactional(readOnly = true)
    public AvailabilityCalendarDto calendar(Long listingId, LocalDate from, LocalDate to) {
        Instant now = clock.instant();
        LocalDate today = now.atZone(AvailabilityEvaluator.ZONE).toLocalDate();
        validate(from, to, today);
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
        long dayStart = startOf(date).getEpochSecond();
        String openLabel;
        String closeLabel;
        long openSecond;
        long closeSecond;
        if (open24x7) {
            openLabel = "00:00";
            closeLabel = "24:00";
            openSecond = 0;
            closeSecond = DAY_SECONDS;
        } else if (rule == null) {
            return new DayAvailabilityDto(date, AvailabilityLevel.CLOSED, null, null, 0);
        } else {
            openLabel = rule.getOpenTime().toString();
            closeLabel = rule.getCloseTime().toString();
            openSecond = rule.getOpenTime().toSecondOfDay();
            // A rule running to 23:59 means "until the end of the day" (see AvailabilityEvaluator.isOpen).
            closeSecond = rule.getCloseTime().equals(LocalTime.of(23, 59)) ? DAY_SECONDS
                    : rule.getCloseTime().toSecondOfDay();
        }
        Span window = new Span(dayStart + openSecond, dayStart + closeSecond);
        long windowLength = window.end() - window.start();
        long open = windowLength * active.size();
        int percent = 100;
        if (open > 0) {
            long taken = 0;
            for (ParkingSlot slot : active) {
                List<Span> spans = new ArrayList<>(listingWide);
                spans.addAll(busy.getOrDefault(slot.getId(), List.of()));
                spans.add(new Span(Long.MIN_VALUE, nowSecond)); // time that has passed cannot be booked
                taken += coveredLength(spans, window);
            }
            percent = (int) (taken * 100 / open);
        }
        AvailabilityLevel level = percent >= FULL_FROM_PERCENT ? AvailabilityLevel.FULL
                : percent >= LIMITED_FROM_PERCENT ? AvailabilityLevel.LIMITED : AvailabilityLevel.AVAILABLE;
        return new DayAvailabilityDto(date, level, openLabel, closeLabel, percent);
    }

    /** Length of the union of the spans after clipping them to the window. */
    private static long coveredLength(List<Span> spans, Span window) {
        List<Span> clipped = new ArrayList<>();
        for (Span s : spans) {
            long start = Math.max(s.start(), window.start());
            long end = Math.min(s.end(), window.end());
            if (start < end) {
                clipped.add(new Span(start, end));
            }
        }
        clipped.sort((a, b) -> Long.compare(a.start(), b.start()));
        long total = 0;
        long curStart = 0;
        long curEnd = 0;
        boolean open = false;
        for (Span s : clipped) {
            if (open && s.start() <= curEnd) {
                curEnd = Math.max(curEnd, s.end());
            } else {
                if (open) {
                    total += curEnd - curStart;
                }
                curStart = s.start();
                curEnd = s.end();
                open = true;
            }
        }
        return open ? total + (curEnd - curStart) : total;
    }

    private static Span span(Instant start, Instant end) {
        return new Span(start.getEpochSecond(), end.getEpochSecond());
    }

    private static Instant startOf(LocalDate date) {
        return date.atStartOfDay(AvailabilityEvaluator.ZONE).toInstant();
    }

    private static void validate(LocalDate from, LocalDate to, LocalDate today) {
        if (from == null || to == null) {
            throw invalid("from and to are required");
        }
        if (to.isBefore(from)) {
            throw invalid("from must not be after to");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw invalid("The range can span at most " + MAX_DAYS + " days");
        }
        if (from.isBefore(today)) {
            throw invalid("from must not be in the past");
        }
        if (to.isAfter(today.plusDays(MAX_AHEAD_DAYS))) {
            throw invalid("to must be within " + MAX_AHEAD_DAYS + " days from today");
        }
    }

    private static ApiException invalid(String message) {
        return ApiException.badRequest("INVALID_DATE_RANGE", message);
    }
}
