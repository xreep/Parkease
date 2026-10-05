package com.smartparking.pricing;

import com.smartparking.common.error.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** A validated booking/search window: quarter-hour aligned, in the future, 1 hour to 90 days long. */
public record TimeWindow(Instant start, Instant end) {

    private static final long QUARTER_SECONDS = 15 * 60;
    private static final long MIN_MINUTES = 60;
    private static final long MAX_MINUTES = 90L * 24 * 60;

    public static TimeWindow of(Instant start, Instant end, Clock clock) {
        if (start == null || end == null) {
            throw invalid("Choose both a start and an end time");
        }
        if (!onQuarter(start) || !onQuarter(end)) {
            throw invalid("Times must be on 15-minute steps");
        }
        if (start.isBefore(floorToQuarter(clock.instant()))) {
            throw invalid("Start time can't be in the past");
        }
        if (!end.isAfter(start)) {
            throw invalid("End time must be after start time");
        }
        long minutes = Duration.between(start, end).toMinutes();
        if (minutes < MIN_MINUTES) {
            throw invalid("Bookings must be at least 1 hour");
        }
        if (minutes > MAX_MINUTES) {
            throw invalid("Bookings can be at most 90 days");
        }
        return new TimeWindow(start, end);
    }

    /** Both null gives an empty result; exactly one null is an error. */
    public static Optional<TimeWindow> optional(Instant start, Instant end, Clock clock) {
        if (start == null && end == null) {
            return Optional.empty();
        }
        return Optional.of(of(start, end, clock));
    }

    public long minutes() {
        return Duration.between(start, end).toMinutes();
    }

    /** Half-open overlap: windows that merely touch do not overlap. */
    public boolean overlaps(Instant otherStart, Instant otherEnd) {
        return otherStart.isBefore(end) && otherEnd.isAfter(start);
    }

    private static boolean onQuarter(Instant t) {
        return t.getNano() == 0 && Math.floorMod(t.getEpochSecond(), QUARTER_SECONDS) == 0;
    }

    private static Instant floorToQuarter(Instant t) {
        long s = t.getEpochSecond();
        return Instant.ofEpochSecond(s - Math.floorMod(s, QUARTER_SECONDS));
    }

    private static ApiException invalid(String message) {
        return ApiException.badRequest("INVALID_TIME_RANGE", message);
    }
}
