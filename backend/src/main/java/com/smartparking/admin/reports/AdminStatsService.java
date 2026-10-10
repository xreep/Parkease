package com.smartparking.admin.reports;

import static com.smartparking.owner.dashboard.DashboardRanges.startOf;

import com.smartparking.admin.reports.AdminReportRepository.CityAggregate;
import com.smartparking.admin.reports.AdminReportRepository.DayAggregate;
import com.smartparking.admin.reports.PlatformUtilization.CityUsage;
import com.smartparking.listing.ListingStatus;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.City;
import com.smartparking.location.CityRepository;
import com.smartparking.location.State;
import com.smartparking.payment.PaymentStatus;
import com.smartparking.user.Role;
import com.smartparking.user.UserRepository;
import com.smartparking.user.UserStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin KPI dashboard (spec §8, §22). Account and listing counts are as of now; everything about bookings is
 * attributed by the IST day the booking was created.
 */
@Service
@RequiredArgsConstructor
public class AdminStatsService {

    static final int TOP = 5;

    /** Payment statuses whose money moved, and those that still hold their money (the fee is only earned then). */
    static final List<String> MOVED = names(PaymentStatus.MONEY_MOVED);
    static final List<String> FEE_BEARING = names(List.of(PaymentStatus.CAPTURED, PaymentStatus.PARTIALLY_REFUNDED));

    private final AdminReportRepository reports;
    private final PlatformUtilization utilization;
    private final UserRepository users;
    private final ParkingListingRepository listings;
    private final CityRepository cities;
    private final Clock clock;

    static List<String> names(List<PaymentStatus> statuses) {
        return statuses.stream().map(Enum::name).toList();
    }

    static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    /** Names of the cities (with their states) behind the aggregate rows. */
    static Map<Long, City> cityNames(CityRepository cities, List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return cities.findAllWithStateByIdIn(ids).stream().collect(Collectors.toMap(City::getId, c -> c));
    }

    @Transactional(readOnly = true)
    public AdminStatsDto stats(LocalDate fromParam, LocalDate toParam) {
        ReportRange range = ReportRange.resolve(fromParam, toParam, clock);
        LocalDate from = range.from();
        LocalDate to = range.to();
        Instant start = startOf(from);
        Instant end = startOf(to.plusDays(1));

        List<CityAggregate> rows = reports.byCity(start, end, 0, 0, MOVED, FEE_BEARING);
        long created = PlatformUtilization.sum(rows, CityAggregate::getBookings);
        long confirmed = PlatformUtilization.sum(rows, CityAggregate::getConfirmed);
        CityUsage usage = utilization.byCity(from, to, 0, 0).values().stream().reduce(PlatformUtilization.NONE,
                CityUsage::plus);

        AdminStatsDto.Users userCounts = new AdminStatsDto.Users(users.countByRole(Role.DRIVER),
                users.countByRole(Role.OWNER),
                users.countByRoleAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(Role.DRIVER, start, end),
                users.countByRoleAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(Role.OWNER, start, end),
                users.countByStatus(UserStatus.SUSPENDED));
        AdminStatsDto.Listings listingCounts = new AdminStatsDto.Listings(
                listings.countByStatus(ListingStatus.APPROVED), listings.countByStatus(ListingStatus.PENDING_REVIEW),
                listings.countByStatus(ListingStatus.SUSPENDED), listings.countByStatus(ListingStatus.PAUSED));
        AdminStatsDto.Bookings bookingCounts = new AdminStatsDto.Bookings(created, confirmed,
                PlatformUtilization.percent(confirmed, created), PlatformUtilization.sum(rows, CityAggregate::getCancelled),
                usage.utilizationPercent());
        AdminStatsDto.Money moneyTotals = new AdminStatsDto.Money(sum(rows, CityAggregate::getGmv),
                sum(rows, CityAggregate::getPlatformFees), sum(rows, CityAggregate::getRefunds),
                sum(rows, CityAggregate::getOwnerEarnings));

        Map<Long, City> named = cityNames(cities, rows.stream().map(CityAggregate::getCityId).toList());
        return new AdminStatsDto(from, to, userCounts, listingCounts, bookingCounts, moneyTotals, topStates(rows, named),
                topCities(rows, named), series(from, to, start, end));
    }

    static BigDecimal sum(List<CityAggregate> rows, java.util.function.Function<CityAggregate, BigDecimal> field) {
        return money(rows.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static List<AdminStatsDto.CityTotal> topCities(List<CityAggregate> rows, Map<Long, City> named) {
        return rows.stream()
                .map(r -> new AdminStatsDto.CityTotal(r.getCityId(), named.get(r.getCityId()).getName(),
                        named.get(r.getCityId()).getState().getName(), r.getBookings(), money(r.getGmv())))
                .sorted(Comparator.comparing(AdminStatsDto.CityTotal::gmv).reversed()
                        .thenComparing(Comparator.comparingLong(AdminStatsDto.CityTotal::bookings).reversed())
                        .thenComparing(AdminStatsDto.CityTotal::name))
                .limit(TOP).toList();
    }

    private static List<AdminStatsDto.StateTotal> topStates(List<CityAggregate> rows, Map<Long, City> named) {
        Map<Long, State> states = new HashMap<>();
        Map<Long, long[]> bookings = new LinkedHashMap<>();
        Map<Long, BigDecimal> gmv = new HashMap<>();
        for (CityAggregate r : rows) {
            State state = named.get(r.getCityId()).getState();
            states.put(state.getId(), state);
            bookings.computeIfAbsent(state.getId(), k -> new long[1])[0] += r.getBookings();
            gmv.merge(state.getId(), r.getGmv(), BigDecimal::add);
        }
        return bookings.entrySet().stream()
                .map(e -> new AdminStatsDto.StateTotal(e.getKey(), states.get(e.getKey()).getName(), e.getValue()[0],
                        money(gmv.get(e.getKey()))))
                .sorted(Comparator.comparing(AdminStatsDto.StateTotal::gmv).reversed()
                        .thenComparing(Comparator.comparingLong(AdminStatsDto.StateTotal::bookings).reversed())
                        .thenComparing(AdminStatsDto.StateTotal::name))
                .limit(TOP).toList();
    }

    /** One point per IST day of the range, zeros for days without bookings. */
    private List<AdminStatsDto.DayPoint> series(LocalDate from, LocalDate to, Instant start, Instant end) {
        Map<LocalDate, DayAggregate> byDay = reports.byDay(start, end, MOVED, FEE_BEARING).stream()
                .collect(Collectors.toMap(d -> LocalDate.parse(d.getDay()), d -> d));
        List<AdminStatsDto.DayPoint> series = new java.util.ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            DayAggregate a = byDay.get(d);
            series.add(a == null ? new AdminStatsDto.DayPoint(d, 0, money(null), money(null))
                    : new AdminStatsDto.DayPoint(d, a.getBookings(), money(a.getGmv()), money(a.getRevenue())));
        }
        return series;
    }
}
