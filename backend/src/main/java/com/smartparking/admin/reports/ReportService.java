package com.smartparking.admin.reports;

import static com.smartparking.admin.reports.AdminStatsService.FEE_BEARING;
import static com.smartparking.admin.reports.AdminStatsService.MOVED;
import static com.smartparking.admin.reports.AdminStatsService.money;
import static com.smartparking.owner.dashboard.DashboardRanges.startOf;

import com.smartparking.admin.reports.AdminReportRepository.CityAggregate;
import com.smartparking.admin.reports.PlatformUtilization.CityUsage;
import com.smartparking.admin.reports.ReportDto.RevenueRow;
import com.smartparking.admin.reports.ReportDto.RevenueTotals;
import com.smartparking.admin.reports.ReportDto.UsageRow;
import com.smartparking.admin.reports.ReportDto.UsageTotals;
import com.smartparking.location.City;
import com.smartparking.location.CityRepository;
import com.smartparking.owner.dashboard.EarningsCsv;
import com.smartparking.owner.dashboard.OwnerEarningsService.CsvExport;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin usage and revenue reports, one row per city, optionally limited to a state or city, with CSV export. The
 * bookings, like in the KPI stats, are those created on the IST days of the range.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    /** Most rows one CSV export holds; more than that sets the truncation header. */
    static final int CSV_MAX_ROWS = 5000;

    static final String USAGE_HEADER = "City,State,Listings,Slots,Bookings,Booked hours,Utilization %,Cancellations";
    static final String REVENUE_HEADER = "City,State,Bookings,GMV,Platform fees,GST,Refunds,Owner earnings";

    int csvMaxRows = CSV_MAX_ROWS;

    private final AdminReportRepository reports;
    private final PlatformUtilization utilization;
    private final CityRepository cities;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ReportDto<UsageRow, UsageTotals> usage(LocalDate fromParam, LocalDate toParam, Long stateId, Long cityId) {
        ReportRange range = ReportRange.resolve(fromParam, toParam, clock);
        long state = stateId == null ? 0 : stateId;
        long city = cityId == null ? 0 : cityId;
        Map<Long, CityAggregate> aggregates = aggregates(range, state, city);
        Map<Long, CityUsage> usage = utilization.byCity(range.from(), range.to(), state, city);
        Set<Long> ids = new HashSet<>(aggregates.keySet());
        ids.addAll(usage.keySet());
        Map<Long, City> named = AdminStatsService.cityNames(cities, List.copyOf(ids));

        List<UsageRow> rows = ids.stream().map(id -> {
            CityAggregate a = aggregates.get(id);
            CityUsage u = usage.getOrDefault(id, PlatformUtilization.NONE);
            return new UsageRow(id, named.get(id).getName(), named.get(id).getState().getName(), u.listings(),
                    u.slots(), a == null ? 0 : a.getBookings(), u.bookedHours(), u.utilizationPercent(),
                    a == null ? 0 : a.getCancelled());
        }).sorted(Comparator.comparingLong(UsageRow::bookings).reversed().thenComparing(UsageRow::cityName)
                .thenComparing(UsageRow::cityId)).toList();

        CityUsage all = usage.values().stream().reduce(PlatformUtilization.NONE, CityUsage::plus);
        return new ReportDto<>(range.from(), range.to(), rows,
                new UsageTotals(all.listings(), all.slots(), sum(rows, UsageRow::bookings), all.bookedHours(),
                        all.utilizationPercent(), sum(rows, UsageRow::cancellations)));
    }

    @Transactional(readOnly = true)
    public ReportDto<RevenueRow, RevenueTotals> revenue(LocalDate fromParam, LocalDate toParam, Long stateId,
                                                        Long cityId) {
        ReportRange range = ReportRange.resolve(fromParam, toParam, clock);
        Map<Long, CityAggregate> aggregates = aggregates(range, stateId == null ? 0 : stateId,
                cityId == null ? 0 : cityId);
        Map<Long, City> named = AdminStatsService.cityNames(cities, List.copyOf(aggregates.keySet()));

        List<RevenueRow> rows = aggregates.values().stream()
                .map(a -> new RevenueRow(a.getCityId(), named.get(a.getCityId()).getName(),
                        named.get(a.getCityId()).getState().getName(), a.getBookings(), money(a.getGmv()),
                        money(a.getPlatformFees()), money(a.getGst()), money(a.getRefunds()),
                        money(a.getOwnerEarnings())))
                .sorted(Comparator.comparing(RevenueRow::gmv).reversed().thenComparing(RevenueRow::cityName)
                        .thenComparing(RevenueRow::cityId))
                .toList();
        return new ReportDto<>(range.from(), range.to(), rows,
                new RevenueTotals(sum(rows, RevenueRow::bookings), total(rows, RevenueRow::gmv),
                        total(rows, RevenueRow::platformFees), total(rows, RevenueRow::gst),
                        total(rows, RevenueRow::refunds), total(rows, RevenueRow::ownerEarnings)));
    }

    /** The report as CSV (BOM, CRLF, formula-guarded cells), cut at {@link #csvMaxRows} rows. */
    @Transactional(readOnly = true)
    public CsvExport usageCsv(LocalDate from, LocalDate to, Long stateId, Long cityId) {
        ReportDto<UsageRow, UsageTotals> report = usage(from, to, stateId, cityId);
        return csv(USAGE_HEADER, report.rows().stream().map(r -> List.of(r.cityName(), r.stateName(),
                String.valueOf(r.listings()), String.valueOf(r.slots()), String.valueOf(r.bookings()),
                r.bookedHours().toPlainString(), r.utilizationPercent().toPlainString(),
                String.valueOf(r.cancellations()))).toList());
    }

    @Transactional(readOnly = true)
    public CsvExport revenueCsv(LocalDate from, LocalDate to, Long stateId, Long cityId) {
        ReportDto<RevenueRow, RevenueTotals> report = revenue(from, to, stateId, cityId);
        return csv(REVENUE_HEADER, report.rows().stream().map(r -> List.of(r.cityName(), r.stateName(),
                String.valueOf(r.bookings()), r.gmv().toPlainString(), r.platformFees().toPlainString(),
                r.gst().toPlainString(), r.refunds().toPlainString(), r.ownerEarnings().toPlainString())).toList());
    }

    /** The IST days a report with these (optional) bounds covers, for naming the download. */
    public ReportRangeView range(LocalDate from, LocalDate to) {
        ReportRange range = ReportRange.resolve(from, to, clock);
        return new ReportRangeView(range.from(), range.to());
    }

    public record ReportRangeView(LocalDate from, LocalDate to) {
    }

    private CsvExport csv(String header, List<List<String>> rows) {
        int cap = csvMaxRows;
        boolean truncated = rows.size() > cap;
        StringBuilder out = new StringBuilder(EarningsCsv.BOM).append(header).append("\r\n");
        for (List<String> row : truncated ? rows.subList(0, cap) : rows) {
            out.append(row.stream().map(EarningsCsv::cell).collect(Collectors.joining(","))).append("\r\n");
        }
        return new CsvExport(out.toString(), truncated);
    }

    private Map<Long, CityAggregate> aggregates(ReportRange range, long stateId, long cityId) {
        Instant start = startOf(range.from());
        Instant end = startOf(range.to().plusDays(1));
        Map<Long, CityAggregate> byCity = new HashMap<>();
        reports.byCity(start, end, stateId, cityId, MOVED, FEE_BEARING).forEach(a -> byCity.put(a.getCityId(), a));
        return byCity;
    }

    private static <T> long sum(List<T> rows, ToLongFunction<T> field) {
        return rows.stream().mapToLong(field).sum();
    }

    private static <T> BigDecimal total(List<T> rows, java.util.function.Function<T, BigDecimal> field) {
        return money(rows.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}
