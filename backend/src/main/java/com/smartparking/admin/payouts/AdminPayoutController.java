package com.smartparking.admin.payouts;

import com.smartparking.availability.AvailabilityEvaluator;
import com.smartparking.common.error.ApiException;
import com.smartparking.common.security.AuthUser;
import com.smartparking.owner.dashboard.EarningsCsv;
import com.smartparking.owner.dashboard.OwnerDashboardController;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/payouts")
@RequiredArgsConstructor
public class AdminPayoutController {

    /** Most owners one CSV export holds; more than that sets the truncation header. */
    static final int CSV_MAX_ROWS = 5000;
    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    static final String CSV_HEADER = "Owner ID,Owner,Email,Pending amount,Earnings,Payout method,Payout details,Held for disputes";

    private final AdminPayoutService service;
    private final Clock clock;

    /** The owners with pending money as JSON, or as a CSV download with {@code format=csv}. */
    @GetMapping
    public ResponseEntity<?> pending(@RequestParam(defaultValue = "json") String format) {
        if ("csv".equalsIgnoreCase(format)) {
            List<PayoutOwnerDto> rows = service.pending();
            boolean truncated = rows.size() > CSV_MAX_ROWS;
            String name = "parkease-pending-payouts-" + clock.instant().atZone(AvailabilityEvaluator.ZONE).toLocalDate()
                    + ".csv";
            ResponseEntity.BodyBuilder response = ResponseEntity.ok().contentType(CSV)
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                    .cacheControl(CacheControl.noStore());
            if (truncated) {
                response.header(OwnerDashboardController.TRUNCATED_HEADER, "true");
            }
            return response.body(csv(truncated ? rows.subList(0, CSV_MAX_ROWS) : rows));
        }
        if (!"json".equalsIgnoreCase(format)) {
            throw ApiException.badRequest("INVALID_PARAMETER", "format must be json or csv");
        }
        return ResponseEntity.ok(service.pending());
    }

    @GetMapping("/{ownerId}/earnings")
    public ResponseEntity<?> earnings(@PathVariable Long ownerId) {
        return ResponseEntity.ok(service.pendingEarnings(ownerId));
    }

    @PostMapping("/mark-paid")
    public MarkPaidResult markPaid(@AuthenticationPrincipal AuthUser admin, @Valid @RequestBody MarkPaidRequest request) {
        return service.markPaid(admin, request);
    }

    static String csv(List<PayoutOwnerDto> rows) {
        StringBuilder out = new StringBuilder(EarningsCsv.BOM).append(CSV_HEADER).append("\r\n");
        for (PayoutOwnerDto r : rows) {
            out.append(String.join(",", List.of(
                    EarningsCsv.cell(String.valueOf(r.ownerId())), EarningsCsv.cell(r.ownerName()),
                    EarningsCsv.cell(r.ownerEmail()), EarningsCsv.cell(r.pendingAmount().setScale(2).toPlainString()),
                    EarningsCsv.cell(String.valueOf(r.earningsCount())), EarningsCsv.cell(r.payoutMethod()),
                    EarningsCsv.cell(r.payoutMasked()),
                    EarningsCsv.cell(r.disputedAmount().setScale(2).toPlainString())))).append("\r\n");
        }
        return out.toString();
    }
}
