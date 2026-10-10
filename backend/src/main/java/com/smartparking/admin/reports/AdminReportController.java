package com.smartparking.admin.reports;

import com.smartparking.common.error.ApiException;
import com.smartparking.owner.dashboard.OwnerDashboardController;
import com.smartparking.owner.dashboard.OwnerEarningsService.CsvExport;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.function.Supplier;
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

    /** Usage per city as JSON, or as a CSV download with {@code format=csv}. */
    @GetMapping("/usage")
    public ResponseEntity<?> usage(@RequestParam(required = false) LocalDate from,
                                   @RequestParam(required = false) LocalDate to,
                                   @RequestParam(required = false) Long stateId,
                                   @RequestParam(required = false) Long cityId,
                                   @RequestParam(defaultValue = "json") String format) {
        return respond("usage", from, to, format, () -> reports.usageCsv(from, to, stateId, cityId),
                () -> reports.usage(from, to, stateId, cityId));
    }

    /** Revenue per city as JSON, or as a CSV download with {@code format=csv}. */
    @GetMapping("/revenue")
    public ResponseEntity<?> revenue(@RequestParam(required = false) LocalDate from,
                                     @RequestParam(required = false) LocalDate to,
                                     @RequestParam(required = false) Long stateId,
                                     @RequestParam(required = false) Long cityId,
                                     @RequestParam(defaultValue = "json") String format) {
        return respond("revenue", from, to, format, () -> reports.revenueCsv(from, to, stateId, cityId),
                () -> reports.revenue(from, to, stateId, cityId));
    }

    private ResponseEntity<?> respond(String kind, LocalDate from, LocalDate to, String format,
                                      Supplier<CsvExport> csv, Supplier<?> json) {
        if ("csv".equalsIgnoreCase(format)) {
            CsvExport export = csv.get();
            ReportService.ReportRangeView range = reports.range(from, to);
            String name = "parkease-" + kind + "-report-" + range.from() + "-to-" + range.to() + ".csv";
            ResponseEntity.BodyBuilder response = ResponseEntity.ok().contentType(CSV)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename(name).build().toString())
                    .cacheControl(CacheControl.noStore());
            if (export.truncated()) {
                response.header(OwnerDashboardController.TRUNCATED_HEADER, "true");
            }
            return response.body(export.text());
        }
        if (!"json".equalsIgnoreCase(format)) {
            throw ApiException.badRequest("INVALID_PARAMETER", "format must be json or csv");
        }
        return ResponseEntity.ok(json.get());
    }
}
