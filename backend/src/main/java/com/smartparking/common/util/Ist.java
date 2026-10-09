package com.smartparking.common.util;

import com.smartparking.availability.AvailabilityEvaluator;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Human-readable Indian Standard Time formatting for emails and documents. */
public final class Ist {

    private static final DateTimeFormatter WINDOW_FORMAT =
            DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH);

    private Ist() {
    }

    /** E.g. {@code Fri 9 Oct, 10:00 AM}. */
    public static String format(Instant time) {
        return WINDOW_FORMAT.format(time.atZone(AvailabilityEvaluator.ZONE));
    }

    /** E.g. {@code 9 Oct 2026, 10:00 AM}. */
    public static String formatDateTime(Instant time) {
        return DATE_TIME_FORMAT.format(time.atZone(AvailabilityEvaluator.ZONE));
    }
}
