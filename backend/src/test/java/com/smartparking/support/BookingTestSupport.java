package com.smartparking.support;

import com.smartparking.booking.Booking;
import com.smartparking.booking.BookingStatus;
import com.smartparking.common.model.VehicleType;
import com.smartparking.listing.ParkingListing;
import com.smartparking.pricing.PricingMode;
import com.smartparking.slot.ParkingSlot;
import com.smartparking.user.User;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

public final class BookingTestSupport {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private BookingTestSupport() {
    }

    /** An unsaved booking with plausible money fields; callers set status-specific fields (e.g. holdExpiresAt). */
    public static Booking booking(User driver, ParkingListing listing, ParkingSlot slot, BookingStatus status,
            Instant start, Instant end) {
        Booking b = new Booking();
        b.setBookingCode("PT" + String.format("%08d", SEQ.incrementAndGet()));
        b.setDriver(driver);
        b.setListing(listing);
        b.setSlot(slot);
        b.setVehicleType(VehicleType.FOUR_WHEELER);
        b.setPlateNumber("MH12AB1234");
        b.setStartTime(start);
        b.setEndTime(end);
        b.setPricingMode(PricingMode.HOURLY);
        b.setPricingBreakdown("test");
        b.setBaseAmount(new BigDecimal("60.00"));
        b.setPlatformFee(new BigDecimal("6.00"));
        b.setGstAmount(new BigDecimal("1.08"));
        b.setTotalAmount(new BigDecimal("67.08"));
        b.setStatus(status);
        return b;
    }
}
