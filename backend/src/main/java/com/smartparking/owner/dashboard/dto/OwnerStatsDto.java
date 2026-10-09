package com.smartparking.owner.dashboard.dto;

import com.smartparking.booking.dto.OwnerBookingDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The owner dashboard. Totals cover bookings starting in [from, to] (IST dates); balances are all-time by earning
 * status; {@code series} has one entry per day of the range, zeros included.
 */
public record OwnerStatsDto(
        LocalDate from,
        LocalDate to,
        Totals totals,
        Balances balances,
        int pendingApprovals,
        List<OwnerBookingDto> upcoming,
        List<DayPoint> series) {

    public record Totals(BigDecimal earningsNet, int bookings, int cancellations, BigDecimal occupancyPercent,
                         BigDecimal avgRating, int reviewCount) {
    }

    public record Balances(BigDecimal held, BigDecimal pendingPayout, BigDecimal paid) {
    }

    public record DayPoint(LocalDate date, BigDecimal earningsNet, int bookings) {
    }
}
