package com.smartparking.payment;

import com.smartparking.common.error.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Refunds captured payments through the provider. The single place that creates {@link Refund} rows. */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final PaymentProvider provider;
    private final RefundRepository refunds;

    public RefundService(PaymentProvider provider, RefundRepository refunds) {
        this.provider = provider;
        this.refunds = refunds;
    }

    /**
     * Refunds the whole payment and records it. Joins the caller's transaction. A provider failure is recorded as a
     * {@link RefundStatus#FAILED} refund (never thrown) so the money is not lost track of; callers can inspect the
     * returned status. On success the payment becomes REFUNDED and the booking's refund amount is set.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Refund refundFull(Payment payment, String reason) {
        Refund refund = new Refund();
        refund.setPayment(payment);
        refund.setAmount(payment.getAmount());
        refund.setReason(reason);
        try {
            ProviderRefund result = provider.refund(payment.getPaymentId(), toPaise(payment.getAmount()), reason);
            refund.setProviderRefundId(result.refundId());
            refund.setStatus(result.status());
        } catch (ApiException e) {
            log.error("Provider refund failed for payment {}: {}", payment.getId(), e.getMessage());
            refund.setStatus(RefundStatus.FAILED);
        }
        refunds.save(refund);
        if (refund.getStatus() != RefundStatus.FAILED) {
            payment.setStatus(PaymentStatus.REFUNDED);
            payment.getBooking().setRefundAmount(payment.getAmount());
        }
        return refund;
    }

    public static long toPaise(BigDecimal amount) {
        return amount.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
