package com.smartparking.booking;

import com.smartparking.common.error.ApiException;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Booking rules: how long an unpaid slot hold lasts, the owner approval window, the per-driver hold cap and how much
 * notice a request-to-book listing needs (its approval deadline is capped at the start, so a request for a start
 * that is too close would be dead on arrival).
 */
@ConfigurationProperties("app.booking")
public record BookingProperties(
        @DefaultValue("10") int holdMinutes,
        @DefaultValue("2") int approvalHours,
        @DefaultValue("3") int maxActiveHolds,
        @DefaultValue("30") int requestMinLeadMinutes) {

    /** Rejects (400 {@code INVALID_TIME_RANGE}) a start too close for a listing that needs the owner's approval. */
    public void requireNoticeForRequest(boolean autoApprove, Instant start, Instant now) {
        if (!autoApprove && start.isBefore(now.plus(Duration.ofMinutes(requestMinLeadMinutes)))) {
            throw ApiException.badRequest("INVALID_TIME_RANGE",
                    "Request-to-book listings need at least " + requestMinLeadMinutes + " minutes' notice");
        }
    }
}
