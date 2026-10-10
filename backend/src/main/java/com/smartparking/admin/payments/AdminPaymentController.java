package com.smartparking.admin.payments;

import com.smartparking.common.security.AuthUser;
import com.smartparking.common.web.PageResponse;
import com.smartparking.payment.PaymentStatus;
import com.smartparking.payment.RefundStatus;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminPaymentController {

    private final AdminPaymentService service;

    /** {@code from} / {@code to}: IST days (inclusive) the payment was created on. */
    @GetMapping("/payments")
    public PageResponse<AdminPaymentDto> payments(@RequestParam(required = false) PaymentStatus status,
                                                  @RequestParam(required = false) String q,
                                                  @RequestParam(required = false)
                                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                  @RequestParam(required = false)
                                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return service.listPayments(status, q, from, to, page, size);
    }

    @GetMapping("/refunds")
    public PageResponse<AdminRefundDto> refunds(@RequestParam(required = false) RefundStatus status,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return service.listRefunds(status, page, size);
    }

    @PostMapping("/refunds/{id}/retry")
    public AdminRefundDto retry(@AuthenticationPrincipal AuthUser admin, @PathVariable Long id) {
        return service.retry(admin, id);
    }
}
