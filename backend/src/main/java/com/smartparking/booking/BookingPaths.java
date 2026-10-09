package com.smartparking.booking;

/** App-relative paths that notifications and emails about a booking link to. */
final class BookingPaths {

    private BookingPaths() {
    }

    /** The driver's page for the booking. */
    static String driver(Booking booking) {
        return "/driver/bookings/" + booking.getId();
    }
}
