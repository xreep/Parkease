package com.smartparking.admin.reports;

import com.smartparking.owner.dashboard.DashboardRanges;
import java.time.Clock;
import java.time.LocalDate;

/** The IST days an admin stats or report request covers: default the last 30 days, at most 366. */
record ReportRange(LocalDate from, LocalDate to) {

    static final int MAX_DAYS = 366;
    static final int DEFAULT_DAYS = 30;

    static ReportRange resolve(LocalDate fromParam, LocalDate toParam, Clock clock) {
        LocalDate to = toParam != null ? toParam : DashboardRanges.dateOf(clock.instant());
        LocalDate from = fromParam != null ? fromParam : to.minusDays(DEFAULT_DAYS - 1L);
        DashboardRanges.validate(from, to, MAX_DAYS);
        return new ReportRange(from, to);
    }
}
