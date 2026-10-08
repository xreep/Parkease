package com.smartparking.payment;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public endpoint called by Razorpay; authenticity comes from the HMAC signature over the raw body. */
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookService service;

    /** The body is read as bytes and decoded as UTF-8 so the signed bytes never depend on a default charset. */
    @PostMapping("/webhook")
    public Map<String, String> webhook(@RequestBody byte[] rawBody,
                                       @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
                                       @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId) {
        return Map.of("status", service.handle(new String(rawBody, StandardCharsets.UTF_8), signature, eventId));
    }
}
