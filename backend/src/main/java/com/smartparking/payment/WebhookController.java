package com.smartparking.payment;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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

    @PostMapping("/webhook")
    public Map<String, String> webhook(@RequestBody String rawBody,
                                       @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
                                       @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId) {
        try {
            return Map.of("status", service.handle(rawBody, signature, eventId));
        } catch (DataIntegrityViolationException e) {
            // Two deliveries of the same event raced; the other one won the unique event id.
            return Map.of("status", WebhookService.DUPLICATE);
        }
    }
}
