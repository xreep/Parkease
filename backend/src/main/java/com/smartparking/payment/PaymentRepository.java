package com.smartparking.payment;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByBookingId(Long bookingId);

    Optional<Payment> findByOrderId(String orderId);

    boolean existsByOrderId(String orderId);

    /** Booking id behind an order, without loading (or locking) any entity. */
    @Query("select p.booking.id from Payment p where p.orderId = :orderId")
    Optional<Long> findBookingIdByOrderId(@Param("orderId") String orderId);

    /** Row-locks the payment so concurrent confirmations (verify call, webhook) are serialised. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.orderId = :orderId")
    Optional<Payment> findByOrderIdForUpdate(@Param("orderId") String orderId);

    /** Row-locks the payment of a booking. Lock order is always payment first, then booking. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.booking.id = :bookingId")
    Optional<Payment> findByBookingIdForUpdate(@Param("bookingId") Long bookingId);

    /** Bookings that lapsed (EXPIRED) while their payment order is still open (CREATED). */
    @Query("select p.booking.id from Payment p where p.status = :paymentStatus "
            + "and p.booking.status = com.smartparking.booking.BookingStatus.EXPIRED order by p.id")
    List<Long> findExpiredBookingIdsWithPaymentIn(@Param("paymentStatus") PaymentStatus paymentStatus, Limit limit);
}
