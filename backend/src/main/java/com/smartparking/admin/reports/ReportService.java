package com.smartparking.admin.reports;

import static com.smartparking.admin.reports.AdminStatsService.FEE_BEARING;
import static com.smartparking.admin.reports.AdminStatsService.MOVED;
import static com.smartparking.admin.reports.ReportMath.money;
import static com.smartparking.admin.reports.ReportMath.sum;
import static com.smartparking.admin.reports.ReportMath.total;
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
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin usage and revenue reports, one row per city, optionally limited to a state or city (id 0 = all), with
 * CSV export. Like the KPI stats, bookings and money are those created on the IST days of the range; booked time is
 * by period.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    /** Most rows one CSV export holds; more than that sets the truncation header. */
    static final int CSV_MAX_ROWS = 5000;

    static final String USAGE_HEADER = "City,State,Listings,Slots,Bookings,Booked hours,Utilization %,Cancellations";
    static final String REVENUE_HEADER = "City,State,Bookings,GMV,Platform fees,GST,Refunds,Owner earnings";
    private static final String TOTAL_LABEL = "Total";

    int csvMaxRows = CSV_MAX_ROWS;

    private final AdminReportRepository reports;
    private final PlatformUtilization utilization;
    private final CityRepository cities;

    @Transactional(readOnly = true)
    ReportDto<UsageRow, UsageTotals> usage(ReportRange range, long stateId, long cityId) {
        Map<Long, CityAggregate> aggregates = aggregates(range, stateId, cityId);
        Map<Long, CityUsage> usage = utilization.byCity(range.from(), range.to(), stateId, cityId);
        Set<Long> ids = new HashSet<>(aggregates.keySet());
        ids.addAll(usage.keySet());
        Map<Long, City> named = AdminStatsService.cityNames(cities, List.copyOf(ids));

        List<UsageRow> rows = ids.stream().map(id -> {
            CityAggregate a = aggregates.get(id);
            CityUsage u = usage.getOrDefault(id, CityUsage.NONE);
            return new UsageRow(id, named.get(id).getName(), named.get(id).getState().getName(), u.listings(),
                    u.slots(), a == null ? 0 : a.getBookings(), u.bookedHours(), u.utilizationPercent(),
                    a == null ? 0 : a.getCancelled());
        }).sorted(Comparator.comparingLong(UsageRow::bookings).reversed().thenComparing(UsageRow::cityName)
                .thenComparing(UsageRow::cityId)).toList();

        CityUsage all = usage.values().stream().reduce(CityUsage.NONE, CityUsage::plus);
        BigDecimal hours = rows.stream().map(UsageRow::bookedHours).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ReportDto<>(range.from(), range.to(), rows,
                new UsageTotals(all.listings(), all.slots(), sum(rows, UsageRow::bookings), hours,
                        all.utilizationPercent(), sum(rows, UsageRow::cancellations)));
    }

    @Transactional(readOnly = true)
    ReportDto<RevenueRow, RevenueTotals> revenue(ReportRange range, long stateId, long cityId) {
        Map<Long, CityAggregate> aggregates = aggregates(range, stateId, cityId);
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

    /**
     * The report as CSV (BOM, CRLF, formula-guarded cells): the rows, cut at {@link #csvMaxRows} (flagged as
     * truncated), then a totals row covering all of them.
     */
    @Transactional(readOnly = true)
    CsvExport usageCsv(ReportRange range, long stateId, long cityId) {
        ReportDto<UsageRow, UsageTotals> report = usage(range, stateId, cityId);
        UsageTotals t = report.totals();
        return csv(USAGE_HEADER, report.rows().stream().map(r -> List.of(r.cityName(), r.stateName(),
                String.valueOf(r.listings()), String.valueOf(r.slots()), String.valueOf(r.bookings()),
                r.bookedHours().toPlainString(), r.utilizationPercent().toPlainString(),
                String.valueOf(r.cancellations()))).toList(),
                List.of(TOTAL_LABEL, "", String.valueOf(t.listings()), String.valueOf(t.slots()),
                        String.valueOf(t.bookings()), t.bookedHours().toPlainString(),
                        t.utilizationPercent().toPlainString(), String.valueOf(t.cancellations())));
    }

    @Transactional(readOnly = true)
    CsvExport revenueCsv(ReportRange range, long stateId, long cityId) {
        ReportDto<RevenueRow, RevenueTotals> report = revenue(range, stateId, cityId);
        RevenueTotals t = report.totals();
        return csv(REVENUE_HEADER, report.rows().stream().map(r -> List.of(r.cityName(), r.stateName(),
                String.valueOf(r.bookings()), r.gmv().toPlainString(), r.platformFees().toPlainString(),
                r.gst().toPlainString(), r.refunds().toPlainString(), r.ownerEarnings().toPlainString())).toList(),
                List.of(TOTAL_LABEL, "", String.valueOf(t.bookings()), t.gmv().toPlainString(),
                        t.platformFees().toPlainString(), t.gst().toPlainString(), t.refunds().toPlainString(),
                        t.ownerEarnings().toPlainString()));
    }

    private CsvExport csv(String header, List<List<String>> rows, List<String> totals) {
        int cap = csvMaxRows;
        boolean truncated = rows.size() > cap;
        StringBuilder out = new StringBuilder(EarningsCsv.BOM).append(header).append("\r\n");
        for (List<String> row : truncated ? rows.subList(0, cap) : rows) {
            appendRow(out, row);
        }
        appendRow(out, totals);
        return new CsvExport(out.toString(), truncated);
    }

    private static void appendRow(StringBuilder out, List<String> row) {
        out.append(row.stream().map(EarningsCsv::cell).collect(Collectors.joining(","))).append("\r\n");
    }

    private Map<Long, CityAggregate> aggregates(ReportRange range, long stateId, long cityId) {
        Instant start = startOf(range.from());
        Instant end = startOf(range.to().plusDays(1));
        Map<Long, CityAggregate> byCity = new HashMap<>();
        reports.byCity(start, end, stateId, cityId, MOVED, FEE_BEARING).forEach(a -> byCity.put(a.getCityId(), a));
        return byCity;
    }
}
