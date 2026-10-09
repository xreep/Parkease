package com.smartparking.owner.dashboard;

import com.smartparking.availability.AvailabilityEvaluator;
import com.smartparking.common.error.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** IST date-range helpers shared by the owner dashboard services. */
final class DashboardRanges {

    private DashboardRanges() {
    }

    /** First instant of the IST day. */
    static Instant startOf(LocalDate date) {
        return date.atStartOfDay(AvailabilityEvaluator.ZONE).toInstant();
    }

    static LocalDate dateOf(Instant instant) {
        return instant.atZone(AvailabilityEvaluator.ZONE).toLocalDate();
    }

    /** Throws 400 {@code INVALID_DATE_RANGE} unless {@code from <= to} and the range spans at most {@code maxDays}. */
    static void validate(LocalDate from, LocalDate to, int maxDays) {
        if (to.isBefore(from)) {
            throw invalid("from must not be after to");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > maxDays) {
            throw invalid("The range can span at most " + maxDays + " days");
        }
    }

    static ApiException invalid(String message) {
        return ApiException.badRequest("INVALID_DATE_RANGE", message);
    }
}
