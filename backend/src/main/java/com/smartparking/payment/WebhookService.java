package com.smartparking.payment;

import com.smartparking.booking.BookingActor;
import com.smartparking.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Applies verified Razorpay webhook events exactly once. */
@Service
@RequiredArgsConstructor
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    public static final String OK = "ok";
    public static final String DUPLICATE = "duplicate";
    public static final String IGNORED = "ignored";

    private static final int MAX_FAILURE_REASON = 300;

    private final PaymentProvider provider;
    private final WebhookEventRepository webhookEvents;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PaymentService paymentService;
    private final Clock clock;

    /**
     * Verifies the signature, then processes the event in one transaction together with its idempotency record, so a
     * failure leaves nothing behind and the provider's retry is processed afresh. Returns ok, duplicate or ignored.
     * A concurrent delivery of the same event id surfaces as a DataIntegrityViolationException from the unique key.
     */
    @Transactional
    public String handle(String rawBody, String signature, String eventIdHeader) {
        if (rawBody == null || !provider.verifyWebhook(rawBody, signature)) {
            throw ApiException.badRequest("INVALID_SIGNATURE", "Webhook signature is invalid");
        }
        String eventId = StringUtils.hasText(eventIdHeader) ? eventIdHeader.trim() : sha256Hex(rawBody);
        if (webhookEvents.existsByProviderEventId(eventId)) {
            return DUPLICATE;
        }
        JSONObject json;
        String eventType;
        try {
            json = new JSONObject(rawBody);
            eventType = json.optString("event", "unknown");
        } catch (JSONException e) {
            throw ApiException.badRequest("INVALID_PAYLOAD", "Webhook body is not valid JSON");
        }

        WebhookEvent record = new WebhookEvent();
        record.setProviderEventId(eventId);
        record.setEventType(eventType);
        record.setPayload(rawBody);
        webhookEvents.saveAndFlush(record); // claims the event id before any side effect

        String result = switch (eventType) {
            case "payment.captured" -> paymentCaptured(entity(json, "payment"));
            case "payment.failed" -> paymentFailed(entity(json, "payment"));
            case "refund.processed" -> refundUpdated(entity(json, "refund"), RefundStatus.PROCESSED);
            case "refund.failed" -> refundUpdated(entity(json, "refund"), RefundStatus.FAILED);
            default -> {
                log.info("Ignoring webhook event {}", eventType);
                yield IGNORED;
            }
        };
        record.setProcessedAt(clock.instant());
        return result;
    }

    private String paymentCaptured(JSONObject payment) {
        String orderId = payment.optString("order_id", null);
        String paymentId = payment.optString("id", null);
        if (!StringUtils.hasText(orderId) || !StringUtils.hasText(paymentId) || payments.findByOrderId(orderId).isEmpty()) {
            log.warn("Ignoring payment.captured for unknown order {}", orderId);
            return IGNORED;
        }
        paymentService.confirmPayment(orderId, paymentId, payment.optString("method", null), BookingActor.SYSTEM);
        return OK;
    }

    private String paymentFailed(JSONObject payment) {
        String orderId = payment.optString("order_id", null);
        Payment found = StringUtils.hasText(orderId) ? payments.findByOrderIdForUpdate(orderId).orElse(null) : null;
        if (found == null) {
            log.warn("Ignoring payment.failed for unknown order {}", orderId);
            return IGNORED;
        }
        // A failed attempt must never undo a payment that already went through on this order.
        if (found.getStatus() == PaymentStatus.CREATED || found.getStatus() == PaymentStatus.FAILED) {
            String reason = payment.optString("error_description", "Payment failed");
            found.setStatus(PaymentStatus.FAILED);
            found.setFailureReason(reason.length() > MAX_FAILURE_REASON ? reason.substring(0, MAX_FAILURE_REASON) : reason);
        }
        return OK;
    }

    private String refundUpdated(JSONObject refund, RefundStatus status) {
        String refundId = refund.optString("id", null);
        Refund found = StringUtils.hasText(refundId) ? refunds.findByProviderRefundId(refundId).orElse(null) : null;
        if (found == null) {
            log.warn("Ignoring refund event for unknown refund {}", refundId);
            return IGNORED;
        }
        found.setStatus(status);
        return OK;
    }

    /** {@code payload.<name>.entity}; an empty object when the event does not carry it. */
    private static JSONObject entity(JSONObject json, String name) {
        JSONObject payload = json.optJSONObject("payload");
        JSONObject wrapper = payload == null ? null : payload.optJSONObject(name);
        JSONObject entity = wrapper == null ? null : wrapper.optJSONObject("entity");
        return entity == null ? new JSONObject() : entity;
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
