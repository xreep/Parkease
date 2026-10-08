package com.smartparking.payment;

import com.smartparking.booking.dto.BookingDetailDto;
import com.smartparking.common.security.AuthUser;
import com.smartparking.common.security.Roles;
import com.smartparking.payment.dto.MockPayRequest;
import com.smartparking.payment.dto.MockPayResponse;
import com.smartparking.payment.dto.VerifyPaymentRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService service;

    @PostMapping("/verify")
    public BookingDetailDto verify(@AuthenticationPrincipal AuthUser principal,
                                   @Valid @RequestBody VerifyPaymentRequest request) {
        Roles.requireDriver(principal);
        return service.verify(principal.id(), request);
    }

    /** Mock provider only (404 otherwise): stands in for the card dialog's success callback. */
    @PostMapping("/mock/pay")
    public MockPayResponse mockPay(@AuthenticationPrincipal AuthUser principal,
                                   @Valid @RequestBody MockPayRequest request) {
        Roles.requireDriver(principal);
        return service.mockPay(principal.id(), request.bookingId());
    }
}
