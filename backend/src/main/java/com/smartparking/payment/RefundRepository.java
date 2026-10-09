package com.smartparking.payment;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    Optional<Refund> findByProviderRefundId(String providerRefundId);

    Optional<Refund> findByProviderPaymentId(String providerPaymentId);

    List<Refund> findByPaymentId(Long paymentId);

    /** Booking behind the refund with this provider refund id, without loading (or locking) any entity. */
    @Query("select r.payment.booking.id from Refund r where r.providerRefundId = :providerRefundId")
    Optional<Long> findBookingIdByProviderRefundId(@Param("providerRefundId") String providerRefundId);

    /** Booking behind a refund, without loading (or locking) any entity. */
    @Query("select r.payment.booking.id from Refund r where r.id = :id")
    Optional<Long> findBookingIdById(@Param("id") Long id);

    /**
     * Refunds in {@code status} that were tried fewer than {@code maxAttempts} times: least-tried first, then the
     * longest untouched, so a refund that keeps failing cannot starve the others.
     */
    @Query("select r.id from Refund r where r.status = :status and r.attempts < :maxAttempts "
            + "order by r.attempts, r.updatedAt, r.id")
    List<Long> findRetryableIds(@Param("status") RefundStatus status, @Param("maxAttempts") int maxAttempts,
                                Limit limit);
}
