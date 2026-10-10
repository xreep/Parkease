package com.smartparking.admin.payments;

import com.smartparking.admin.AdminFilters;
import com.smartparking.admin.audit.AdminAuditService;
import com.smartparking.common.error.ApiException;
import com.smartparking.common.security.AuthUser;
import com.smartparking.common.web.PageResponse;
import com.smartparking.payment.Payment;
import com.smartparking.payment.PaymentRepository;
import com.smartparking.payment.PaymentStatus;
import com.smartparking.payment.Refund;
import com.smartparking.payment.RefundRepository;
import com.smartparking.payment.RefundService;
import com.smartparking.payment.RefundStatus;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-only lists of payments and refunds, and the manual retry of a FAILED refund. */
@Service
@RequiredArgsConstructor
public class AdminPaymentService {

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final RefundService refundService;
    private final AdminAuditService audit;
    private final PlatformTransactionManager txManager;

    @Transactional(readOnly = true)
    public PageResponse<AdminPaymentDto> listPayments(PaymentStatus status, String q, LocalDate from, LocalDate to,
                                                      int page, int size) {
        AdminFilters.Range range = AdminFilters.istDays(from, to);
        return PageResponse.from(payments.adminSearch(status, range.from(), range.to(), AdminFilters.likePattern(q),
                paged(page, size)).map(AdminPaymentService::toDto));
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminRefundDto> listRefunds(RefundStatus status, int page, int size) {
        return PageResponse.from(refunds.adminSearch(status, paged(page, size)).map(AdminPaymentService::toDto));
    }

    /**
     * One more provider attempt for a FAILED refund, whatever the attempt count (the automatic retries stop at
     * {@value RefundService#MAX_ATTEMPTS}). The refund service runs it in its own transaction under the payment and
     * booking locks, so this method is deliberately not transactional itself. The outcome is audited either way and
     * returned: a refund that fails again simply stays FAILED.
     */
    public AdminRefundDto retry(AuthUser admin, Long refundId) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setReadOnly(true);
        Refund before = tx.execute(s -> refunds.findById(refundId).orElse(null));
        if (before == null) {
            throw ApiException.notFound("Refund not found");
        }
        if (before.getStatus() != RefundStatus.FAILED) {
            throw ApiException.conflict("NOT_RETRYABLE", "Only failed refunds can be retried (this one is "
                    + before.getStatus() + ")");
        }
        boolean issued = refundService.retry(refundId, true);
        return new TransactionTemplate(txManager).execute(s -> {
            Refund after = refunds.findById(refundId).orElseThrow();
            audit.record(admin, "REFUND_RETRIED", "REFUND", refundId,
                    issued ? "Retry succeeded (" + after.getStatus() + ")" : "Retry failed, refund is still FAILED");
            return toDto(after);
        });
    }

    private static AdminPaymentDto toDto(Payment p) {
        return new AdminPaymentDto(p.getId(), p.getBooking().getId(), p.getBooking().getBookingCode(),
                p.getBooking().getDriver().getEmail(), p.getProvider(), p.getOrderId(), p.getPaymentId(), p.getStatus(),
                p.getAmount(), p.getBooking().getRefundAmount(), p.getCreatedAt(), p.getCapturedAt());
    }

    private static AdminRefundDto toDto(Refund r) {
        return new AdminRefundDto(r.getId(), r.getPayment().getId(), r.getPayment().getBooking().getId(),
                r.getPayment().getBooking().getBookingCode(), r.getAmount(), r.getStatus(), r.getAttempts(),
                r.getProviderRefundId(), r.getReason(), r.getCreatedAt(),
                r.getStatus() == RefundStatus.FAILED ? r.getFailureReason() : null);
    }

    private static PageRequest paged(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }
}
