package com.smartparking.booking;

import com.smartparking.listing.CancellationPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/**
 * What a driver gets back for cancelling a confirmed booking, from the listing's cancellation policy and how long
 * before the start the cancellation happens. The refund is a share of the BASE amount only; the platform fee and GST
 * are never refunded.
 *
 * <ul>
 *   <li>FLEXIBLE: 60 minutes or more before the start 100%, otherwise 50%</li>
 *   <li>MODERATE: 24 hours (1440 minutes) or more 100%, 2 hours (120 minutes) or more 50%, otherwise 0%</li>
 *   <li>STRICT: 48 hours (2880 minutes) or more 50%, otherwise 0%</li>
 * </ul>
 *
 * Thresholds compare whole minutes (a partial minute does not count), so 59 minutes 59 seconds is still 59 minutes.
 */
public final class CancellationPolicyCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);

    /** {@code refundAmount} has two decimals, {@code hoursBeforeStart} one. */
    public record Result(int percent, BigDecimal refundAmount, BigDecimal hoursBeforeStart) {
    }

    private CancellationPolicyCalculator() {
    }

    public static Result preview(CancellationPolicy policy, Instant start, Instant now, BigDecimal base) {
        long minutes = Duration.between(now, start).toMinutes();
        int percent = switch (policy) {
            case FLEXIBLE -> minutes >= 60 ? 100 : 50;
            case MODERATE -> minutes >= 1440 ? 100 : minutes >= 120 ? 50 : 0;
            case STRICT -> minutes >= 2880 ? 50 : 0;
        };
        BigDecimal refund = base.multiply(BigDecimal.valueOf(percent)).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal hours = BigDecimal.valueOf(minutes).divide(MINUTES_PER_HOUR, 1, RoundingMode.HALF_UP);
        return new Result(percent, refund, hours);
    }
}
