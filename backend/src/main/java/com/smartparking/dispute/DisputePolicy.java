package com.smartparking.dispute;

import com.smartparking.booking.Booking;
import com.smartparking.booking.BookingStatus;
import java.time.Duration;
import java.time.Instant;

/** When a driver may raise a dispute about a booking. */
public final class DisputePolicy {

    /** How long after a booking ends a dispute can still be raised. */
    public static final Duration WINDOW = Duration.ofDays(7);

    private DisputePolicy() {
    }

    /** Why no dispute can be raised for the booking now, or null when one can (an unresolved dispute counts). */
    public static String notDisputableReason(Booking booking, boolean hasUnresolved, Instant now) {
        BookingStatus s = booking.getStatus();
        if (s != BookingStatus.CONFIRMED && s != BookingStatus.ACTIVE && s != BookingStatus.COMPLETED) {
            return "Problems can only be reported for confirmed, active or completed bookings";
        }
        if (now.isAfter(booking.getEndTime().plus(WINDOW))) {
            return "Problems must be reported within 7 days after the booking ends";
        }
        return hasUnresolved ? "There is already an open report for this booking" : null;
    }
}
