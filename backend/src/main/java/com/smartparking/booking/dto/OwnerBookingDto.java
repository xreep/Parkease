package com.smartparking.booking.dto;

import com.smartparking.booking.BookingStatus;
import com.smartparking.common.model.VehicleType;
import java.math.BigDecimal;
import java.time.Instant;

/** A booking as the listing's owner sees it: the owner's share ({@code baseAmount}) and the driver's first name only. */
public record OwnerBookingDto(
        Long id,
        String bookingCode,
        BookingStatus status,
        Long listingId,
        String listingTitle,
        String slotLabel,
        Instant startTime,
        Instant endTime,
        VehicleType vehicleType,
        String plateNumber,
        String driverFirstName,
        BigDecimal baseAmount,
        Instant approvalDeadline,
        Instant createdAt) {
}
