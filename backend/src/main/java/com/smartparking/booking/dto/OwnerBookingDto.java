package com.smartparking.booking.dto;

import com.smartparking.booking.BookingStatus;
import com.smartparking.common.model.VehicleType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A booking as the listing's owner sees it: the owner's share ({@code baseAmount}), what of it the owner still earns
 * ({@code ownerNet}: null when there is no earning, zero once the booking was refunded away) and the driver's first
 * name only.
 */
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
        BigDecimal ownerNet,
        Instant approvalDeadline,
        Instant createdAt) {
}
