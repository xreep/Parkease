package com.smartparking.payment;

import com.smartparking.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "refunds")
public class Refund extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @Column(name = "provider_refund_id")
    private String providerRefundId;

    /**
     * The provider payment this refund returns, only when it is not the payment's own (an extra payment captured for
     * the same order). Null for ordinary refunds, which count towards the payment's refunded total.
     */
    @Column(name = "provider_payment_id")
    private String providerPaymentId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RefundStatus status;

    private String reason;

    private String failureReason;

    @Enumerated(EnumType.STRING)
    private RefundNotice notice;

    /** Provider attempts so far; the first try counts. */
    @Column(nullable = false)
    private int attempts = 1;
}
