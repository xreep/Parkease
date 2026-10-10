package com.smartparking.admin.reports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** The admin KPI dashboard: counts as of now, bookings and money for the bookings created in [from, to] (IST days). */
public record AdminStatsDto(LocalDate from, LocalDate to, Users users, Listings listings, Bookings bookings,
                            Money money, List<StateTotal> topStates, List<CityTotal> topCities,
                            List<DayPoint> series) {

    public record Users(long drivers, long owners, long newDrivers, long newOwners, long suspended) {
    }

    public record Listings(long approved, long pendingReview, long suspended, long paused) {
    }

    public record Bookings(long created, long confirmed, BigDecimal conversionPercent, long cancelled,
                           BigDecimal utilizationPercent) {
    }

    public record Money(BigDecimal gmv, BigDecimal platformRevenue, BigDecimal refunds, BigDecimal ownerEarnings) {
    }

    public record StateTotal(Long stateId, String name, long bookings, BigDecimal gmv) {
    }

    public record CityTotal(Long cityId, String name, String stateName, long bookings, BigDecimal gmv) {
    }

    public record DayPoint(LocalDate date, long bookings, BigDecimal gmv, BigDecimal revenue) {
    }
}
