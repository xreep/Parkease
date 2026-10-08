package com.smartparking.booking;

import com.smartparking.common.error.ApiException;
import com.smartparking.payment.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one way to row-lock a booking together with its payment. The lock order is fixed everywhere in the code base:
 * the PAYMENT row first, then the BOOKING row, so that a payment confirmation, an owner decision and the background
 * jobs can never deadlock each other.
 */
@Component
@RequiredArgsConstructor
public class BookingLocks {

    private final PaymentRepository payments;
    private final BookingRepository bookings;

    /** Locks the booking's payment (if it has one) and then the booking; returns the freshly read booking. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Booking lock(Long bookingId) {
        payments.findByBookingIdForUpdate(bookingId);
        return bookings.findByIdForUpdate(bookingId).orElseThrow(() -> ApiException.notFound("Booking not found"));
    }
}
