package com.smartparking.admin;

import com.smartparking.availability.AvailabilityEvaluator;
import com.smartparking.common.error.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;

/** Shared parsing of admin list filters. */
public final class AdminFilters {

    /** Stands for "no lower bound" / "no upper bound" in date-range queries. */
    private static final Instant FAR_PAST = Instant.parse("1970-01-01T00:00:00Z");
    private static final Instant FAR_FUTURE = Instant.parse("2999-12-31T00:00:00Z");

    /** [from, to) as instants. */
    public record Range(Instant from, Instant to) {
    }

    private AdminFilters() {
    }

    /** Lower-cased {@code %q%} with LIKE wildcards in the text escaped (backslash); blank matches everything. */
    public static String likePattern(String q) {
        if (q == null || q.isBlank()) {
            return "%";
        }
        return "%" + q.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                + "%";
    }

    /** The IST days {@code from} to {@code to} inclusive (either may be open); 400 {@code INVALID_DATE_RANGE}. */
    public static Range istDays(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.badRequest("INVALID_DATE_RANGE", "from must not be after to");
        }
        Instant start = from == null ? FAR_PAST : from.atStartOfDay(AvailabilityEvaluator.ZONE).toInstant();
        Instant end = to == null ? FAR_FUTURE : to.plusDays(1).atStartOfDay(AvailabilityEvaluator.ZONE).toInstant();
        return new Range(start, end);
    }
}
