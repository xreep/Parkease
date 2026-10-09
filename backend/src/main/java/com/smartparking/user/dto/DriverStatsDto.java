package com.smartparking.user.dto;

import java.math.BigDecimal;

/**
 * {@code totalBookings} counts bookings that were paid for (so not unpaid or expired holds); {@code amountSpent} is
 * captured minus refunded; {@code hoursParked} (one decimal) covers completed bookings; {@code pendingReviews} is the
 * number of completed bookings that can still be reviewed and {@code reviewBookingId} the one that ended last (null
 * when there is none).
 */
public record DriverStatsDto(
        int totalBookings,
        int completedBookings,
        BigDecimal amountSpent,
        BigDecimal hoursParked,
        int pendingReviews,
        Long reviewBookingId) {
}
