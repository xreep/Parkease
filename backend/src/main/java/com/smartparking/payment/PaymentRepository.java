package com.smartparking.payment;

import com.smartparking.booking.BookingStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
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

    /**
     * Bookings that are EXPIRED, whose payment is in one of {@code paymentStatuses} and that have no EXPIRED entry in
     * their history yet (the slot allocator expires lapsed holds in bulk without writing one).
     */
    @Query("select p.booking.id from Payment p where p.status in :paymentStatuses "
            + "and p.booking.status = com.smartparking.booking.BookingStatus.EXPIRED "
            + "and not exists (select 1 from BookingEvent e where e.booking = p.booking "
            + "and e.toStatus = com.smartparking.booking.BookingStatus.EXPIRED) order by p.id")
    List<Long> findExpiredBookingIdsWithoutExpiredEvent(
            @Param("paymentStatuses") Collection<PaymentStatus> paymentStatuses, Limit limit);

    /**
     * Order ids of unconfirmed payments of one provider whose booking could still be (or have been) paid, created in
     * [{@code from}, {@code to}); newest first, so a backlog never starves the orders customers are waiting on.
     * Holds the driver cancelled before paying ({@code cancelled} booking, payment FAILED with no provider payment)
     * count too: the checkout may have been completed anyway, and that money has to go back.
     */
    @Query("select p.orderId from Payment p where p.provider = :provider and p.createdAt >= :from and p.createdAt < :to "
            + "and ((p.status in :paymentStatuses and p.booking.status in :bookingStatuses) "
            + "or (p.status = :failed and p.paymentId is null and p.booking.status = :cancelled)) "
            + "order by p.id desc")
    List<String> findOrderIdsToReconcile(@Param("provider") PaymentProviderType provider,
                                         @Param("paymentStatuses") Collection<PaymentStatus> paymentStatuses,
                                         @Param("bookingStatuses") Collection<BookingStatus> bookingStatuses,
                                         @Param("failed") PaymentStatus failed,
                                         @Param("cancelled") BookingStatus cancelled,
                                         @Param("from") Instant from, @Param("to") Instant to, Limit limit);
}
