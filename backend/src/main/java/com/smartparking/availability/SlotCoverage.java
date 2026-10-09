package com.smartparking.availability;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Shared slot-time arithmetic for the public availability calendar and the owner dashboard's occupancy: the open
 * window of a listing on a day and the length of the union of busy spans inside it. All times are epoch seconds.
 */
public final class SlotCoverage {

    public static final long DAY_SECONDS = 24 * 3600L;

    private static final LocalTime END_OF_DAY_RULE = LocalTime.of(23, 59);

    private SlotCoverage() {
    }

    /** A half-open span of epoch seconds. */
    public record Span(long start, long end) {

        public long length() {
            return end - start;
        }
    }

    /** The open window of an IST day with the labels to show ("24:00" for a listing open around the clock). */
    public record OpenWindow(String openLabel, String closeLabel, Span span) {
    }

    /**
     * The window in which the listing is open on {@code date}, or empty when it is closed that day. {@code rule} is the
     * weekly rule for that weekday (ignored for 24x7 listings; null means closed). A rule running to 23:59 means
     * "until the end of the day" (see {@link AvailabilityEvaluator#isOpen}).
     */
    public static Optional<OpenWindow> openWindow(boolean open24x7, AvailabilityRule rule, LocalDate date) {
        long dayStart = date.atStartOfDay(AvailabilityEvaluator.ZONE).toEpochSecond();
        if (open24x7) {
            return Optional.of(new OpenWindow("00:00", "24:00", new Span(dayStart, dayStart + DAY_SECONDS)));
        }
        if (rule == null) {
            return Optional.empty();
        }
        long close = rule.getCloseTime().equals(END_OF_DAY_RULE) ? DAY_SECONDS : rule.getCloseTime().toSecondOfDay();
        return Optional.of(new OpenWindow(rule.getOpenTime().toString(), rule.getCloseTime().toString(),
                new Span(dayStart + rule.getOpenTime().toSecondOfDay(), dayStart + close)));
    }

    /** Length of the union of the spans after clipping them to the window. */
    public static long coveredLength(List<Span> spans, Span window) {
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
}
