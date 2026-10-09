package com.smartparking.review;

import com.smartparking.booking.Booking;
import com.smartparking.booking.BookingStatus;
import java.time.Duration;
import java.time.Instant;

/** When a booking may still be reviewed. */
public final class ReviewPolicy {

    /** How long after a booking ended its driver can still review it. */
    public static final Duration WINDOW = Duration.ofDays(30);

    private ReviewPolicy() {
    }

    /** Why the booking cannot be reviewed now (ignoring an existing review), or null when it can. */
    public static String notReviewableReason(Booking booking, Instant now) {
        if (booking.getStatus() != BookingStatus.COMPLETED) {
            return "Only completed bookings can be reviewed";
        }
        if (booking.getEndTime().isBefore(now.minus(WINDOW))) {
            return "Bookings can be reviewed for 30 days after they end";
        }
        return null;
    }
}
