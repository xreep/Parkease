package com.smartparking.admin.reports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** A per-city admin report with its totals. */
public record ReportDto<R, T>(LocalDate from, LocalDate to, List<R> rows, T totals) {

    public record UsageRow(Long cityId, String cityName, String stateName, long listings, long slots, long bookings,
                           BigDecimal bookedHours, BigDecimal utilizationPercent, long cancellations) {
    }

    public record UsageTotals(long listings, long slots, long bookings, BigDecimal bookedHours,
                              BigDecimal utilizationPercent, long cancellations) {
    }

    public record RevenueRow(Long cityId, String cityName, String stateName, long bookings, BigDecimal gmv,
                             BigDecimal platformFees, BigDecimal gst, BigDecimal refunds, BigDecimal ownerEarnings) {
    }

    public record RevenueTotals(long bookings, BigDecimal gmv, BigDecimal platformFees, BigDecimal gst,
                                BigDecimal refunds, BigDecimal ownerEarnings) {
    }
}
