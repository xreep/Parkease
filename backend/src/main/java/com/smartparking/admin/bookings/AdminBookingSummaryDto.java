package com.smartparking.admin.bookings;

import com.smartparking.booking.BookingStatus;
import com.smartparking.payment.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record AdminBookingSummaryDto(Long id, String bookingCode, BookingStatus status, String listingTitle,
                                     String cityName, String driverName, String driverEmail, String ownerName,
                                     Instant startTime, Instant endTime, BigDecimal totalAmount,
                                     BigDecimal refundAmount, PaymentStatus paymentStatus, Instant createdAt) {
}
