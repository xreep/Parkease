package com.smartparking.booking.dto;

import com.smartparking.booking.BookingActor;
import com.smartparking.booking.BookingStatus;
import com.smartparking.common.model.VehicleType;
import com.smartparking.payment.PaymentStatus;
import com.smartparking.pricing.PricingMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** The booking summary fields plus pricing, place, payment and the status history (flat JSON). */
public record BookingDetailDto(
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
        Instant createdAt,
        String address,
        double lat,
        double lng,
        String slotLabel,
        PricingMode pricingMode,
        String pricingBreakdown,
        BigDecimal baseAmount,
        BigDecimal platformFee,
        BigDecimal gstAmount,
        BigDecimal refundAmount,
        Instant holdExpiresAt,
        Instant approvalDeadline,
        Instant confirmedAt,
        String cancelReason,
        BookingActor cancelledBy,
        PaymentStatus paymentStatus,
        String invoiceNumber,
        boolean autoApprove,
        String ownerFirstName,
        List<Event> events) {

    public record Event(BookingStatus fromStatus, BookingStatus toStatus, BookingActor actor, String note, Instant at) {
    }
}
