package com.smartparking.admin.reports;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/stats")
@RequiredArgsConstructor
public class AdminStatsController {

    private final AdminStatsService stats;

    /** KPIs for the IST days {@code from..to} (default the last 30 days, at most 366). */
    @GetMapping
    public AdminStatsDto stats(@RequestParam(required = false) LocalDate from,
                               @RequestParam(required = false) LocalDate to) {
        return stats.stats(from, to);
    }
}
