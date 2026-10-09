package com.smartparking.booking.dto;

import com.smartparking.booking.BookingStatus;
import com.smartparking.common.model.VehicleType;
import java.math.BigDecimal;
import java.time.Instant;

public record BookingSummaryDto(
        Long id,
        String bookingCode,
        BookingStatus status,
        Long listingId,
        String listingTitle,
        String cityName,
        String coverPhotoUrl,
        Instant startTime,
        Instant endTime,
        VehicleType vehicleType,
        String plateNumber,
        BigDecimal totalAmount,
        Instant createdAt) {
}
