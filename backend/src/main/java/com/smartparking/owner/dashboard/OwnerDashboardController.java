package com.smartparking.owner.dashboard;

import com.smartparking.availability.AvailabilityEvaluator;
import com.smartparking.common.error.ApiException;
import com.smartparking.common.security.AuthUser;
import com.smartparking.earning.EarningStatus;
import com.smartparking.owner.dashboard.dto.OwnerCalendarDto;
import com.smartparking.owner.dashboard.dto.OwnerStatsDto;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The owner's dashboard numbers, earnings ledger and slot calendar (OWNER role enforced for /owner/**). */
@RestController
@RequestMapping("/api/v1/owner")
@RequiredArgsConstructor
public class OwnerDashboardController {

    /** Set on a CSV export that stopped at the row cap. */
    public static final String TRUNCATED_HEADER = "X-Truncated";

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final OwnerStatsService stats;
    private final OwnerEarningsService earnings;
    private final OwnerCalendarService calendar;
    private final Clock clock;

    @GetMapping("/stats")
    public OwnerStatsDto stats(@AuthenticationPrincipal AuthUser principal,
                               @RequestParam(required = false) LocalDate from,
                               @RequestParam(required = false) LocalDate to) {
        return stats.stats(principal.id(), from, to);
    }

    /** The ledger as JSON, or as a CSV download with {@code format=csv}. */
    @GetMapping("/earnings")
    public ResponseEntity<?> earnings(@AuthenticationPrincipal AuthUser principal,
                                      @RequestParam(required = false) EarningStatus status,
                                      @RequestParam(required = false) LocalDate from,
                                      @RequestParam(required = false) LocalDate to,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size,
                                      @RequestParam(defaultValue = "json") String format) {
        if ("csv".equalsIgnoreCase(format)) {
            String name = "parkease-earnings-" + clock.instant().atZone(AvailabilityEvaluator.ZONE).toLocalDate()
                    + ".csv";
            OwnerEarningsService.CsvExport export = earnings.csv(principal.id(), status, from, to);
            ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                    .contentType(CSV)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename(name).build().toString())
                    .cacheControl(CacheControl.noStore());
            if (export.truncated()) {
                response.header(TRUNCATED_HEADER, "true");
            }
            return response.body(export.text());
        }
        if (!"json".equalsIgnoreCase(format)) {
            throw ApiException.badRequest("INVALID_PARAMETER", "format must be json or csv");
        }
        return ResponseEntity.ok(earnings.list(principal.id(), status, from, to, page, size));
    }

    @GetMapping("/calendar")
    public OwnerCalendarDto calendar(@AuthenticationPrincipal AuthUser principal, @RequestParam Long listingId,
                                     @RequestParam(required = false) LocalDate from,
                                     @RequestParam(required = false) LocalDate to) {
        return calendar.calendar(principal.id(), listingId, from, to);
    }
}
