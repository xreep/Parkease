package com.smartparking.owner.dashboard.dto;

import com.smartparking.booking.BookingStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record OwnerCalendarDto(
        Long listingId,
        LocalDate from,
        LocalDate to,
        List<CalendarSlot> slots,
        List<CalendarBooking> bookings,
        List<CalendarBlock> blocks) {

    public record CalendarSlot(Long id, String label) {
    }

    public record CalendarBooking(Long id, String bookingCode, Long slotId, Instant startTime, Instant endTime,
                                  BookingStatus status, String driverName) {
    }

    public record CalendarBlock(Long id, Long slotId, Instant startTime, Instant endTime, String reason) {
    }
}
