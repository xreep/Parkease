package com.smartparking.admin.reports;

import com.smartparking.common.error.ApiException;
import com.smartparking.owner.dashboard.OwnerDashboardController;
import com.smartparking.owner.dashboard.OwnerEarningsService.CsvExport;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/reports")
@RequiredArgsConstructor
public class AdminReportController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final ReportService reports;
    private final Clock clock;

    /** Usage per city as JSON, or as a CSV download with {@code format=csv}. */
    @GetMapping("/usage")
    public ResponseEntity<?> usage(@RequestParam(required = false) LocalDate from,
                                   @RequestParam(required = false) LocalDate to,
                                   @RequestParam(required = false) Long stateId,
                                   @RequestParam(required = false) Long cityId,
                                   @RequestParam(defaultValue = "json") String format) {
        boolean csv = isCsv(format);
        ReportRange range = ReportRange.resolve(from, to, clock);
        long state = filter(stateId, "stateId");
        long city = filter(cityId, "cityId");
        return csv ? csvResponse("usage", range, reports.usageCsv(range, state, city))
                : ResponseEntity.ok(reports.usage(range, state, city));
    }

    /** Revenue per city as JSON, or as a CSV download with {@code format=csv}. */
    @GetMapping("/revenue")
    public ResponseEntity<?> revenue(@RequestParam(required = false) LocalDate from,
                                     @RequestParam(required = false) LocalDate to,
                                     @RequestParam(required = false) Long stateId,
                                     @RequestParam(required = false) Long cityId,
                                     @RequestParam(defaultValue = "json") String format) {
        boolean csv = isCsv(format);
        ReportRange range = ReportRange.resolve(from, to, clock);
        long state = filter(stateId, "stateId");
        long city = filter(cityId, "cityId");
        return csv ? csvResponse("revenue", range, reports.revenueCsv(range, state, city))
                : ResponseEntity.ok(reports.revenue(range, state, city));
    }

    private static boolean isCsv(String format) {
        if ("csv".equalsIgnoreCase(format)) {
            return true;
        }
        if (!"json".equalsIgnoreCase(format)) {
            throw ApiException.badRequest("INVALID_PARAMETER", "format must be json or csv");
        }
        return false;
    }

    /** An optional id filter as the repository's "0 = all"; an id that is given must be positive. */
    private static long filter(Long id, String name) {
        if (id == null) {
            return 0;
        }
        if (id <= 0) {
            throw ApiException.badRequest("INVALID_PARAMETER", name + " must be a positive id");
        }
        return id;
    }

    private static ResponseEntity<String> csvResponse(String kind, ReportRange range, CsvExport export) {
        String name = "parkease-" + kind + "-report-" + range.from() + "-to-" + range.to() + ".csv";
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .cacheControl(CacheControl.noStore());
        if (export.truncated()) {
            response.header(OwnerDashboardController.TRUNCATED_HEADER, "true");
        }
        return response.body(export.text());
    }
}
