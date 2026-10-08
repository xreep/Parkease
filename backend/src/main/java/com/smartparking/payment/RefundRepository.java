package com.smartparking.payment;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    Optional<Refund> findByProviderRefundId(String providerRefundId);

    /** Payment behind a refund, without loading (or locking) any entity. */
    @Query("select r.payment.id from Refund r where r.id = :id")
    Optional<Long> findPaymentIdById(@Param("id") Long id);

    /** Refunds in {@code status} that were tried fewer than {@code maxAttempts} times, oldest first. */
    @Query("select r.id from Refund r where r.status = :status and r.attempts < :maxAttempts order by r.id")
    List<Long> findRetryableIds(@Param("status") RefundStatus status, @Param("maxAttempts") int maxAttempts,
                                Limit limit);
}
