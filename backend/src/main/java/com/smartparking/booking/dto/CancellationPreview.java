package com.smartparking.booking.dto;

import com.smartparking.listing.CancellationPolicy;
import java.math.BigDecimal;

/**
 * What cancelling a booking would do right now. {@code reason} says why it cannot be cancelled (null when it can) and
 * {@code policy} is the listing's policy when it decides the refund (null otherwise). {@code refundAmount} is what is
 * paid back, {@code nonRefundableAmount} what the driver keeps paying; {@code hoursBeforeStart} has one decimal.
 */
public record CancellationPreview(
        boolean cancellable,
        String reason,
        CancellationPolicy policy,
        int refundPercent,
        BigDecimal refundAmount,
        BigDecimal nonRefundableAmount,
        BigDecimal hoursBeforeStart) {
}
