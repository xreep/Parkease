package com.smartparking.payment;

import com.smartparking.booking.BookingActor;
import com.smartparking.common.error.ApiException;
import com.smartparking.common.util.SqlStates;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Applies verified Razorpay webhook events once. The event id is claimed in its own short transaction; the claim
 * only counts as "done" once {@code processed_at} is set after the event was applied, so a delivery that failed
 * midway is retried by the provider rather than swallowed as a duplicate.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    public static final String OK = "ok";
    public static final String DUPLICATE = "duplicate";
    public static final String IGNORED = "ignored";

    private static final int MAX_EVENT_ID = 100;
    private static final int MAX_FAILURE_REASON = 300;

    private final PaymentProvider provider;
    private final WebhookEventRepository webhookEvents;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PaymentService paymentService;
    private final RefundService refundService;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate newTx;

    public WebhookService(PaymentProvider provider, WebhookEventRepository webhookEvents, PaymentRepository payments,
                          RefundRepository refunds, PaymentService paymentService, RefundService refundService, Clock clock,
                          PlatformTransactionManager txManager) {
        this.provider = provider;
        this.webhookEvents = webhookEvents;
        this.payments = payments;
        this.refunds = refunds;
        this.paymentService = paymentService;
        this.refundService = refundService;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private record Claim(Long id, boolean processed) {
    }

    /**
     * Returns ok, duplicate (the event was already applied) or ignored. Bad signature or body is a 4xx; anything that
     * goes wrong while applying the event is a 500 so the provider redelivers it.
     */
    public String handle(String rawBody, String signature, String eventIdHeader) {
        if (rawBody == null || !provider.verifyWebhook(rawBody, signature)) {
            throw ApiException.badRequest("INVALID_SIGNATURE", "Webhook signature is invalid");
        }
        JSONObject json;
        try {
            json = new JSONObject(rawBody);
        } catch (JSONException e) {
            throw ApiException.badRequest("INVALID_PAYLOAD", "Webhook body is not valid JSON");
        }
        String eventType = json.optString("event", "unknown");
        String eventId = eventId(eventIdHeader, rawBody);

        Claim claim = claim(eventId, eventType, rawBody);
        if (claim.processed()) {
            return DUPLICATE;
        }
        try {
            String result = apply(eventType, json);
            tx.executeWithoutResult(s -> webhookEvents.findById(claim.id()).orElseThrow().setProcessedAt(clock.instant()));
            return result;
        } catch (RuntimeException e) {
            log.error("Webhook event {} ({}) failed; the provider will retry", eventId, eventType, e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "WEBHOOK_PROCESSING_FAILED",
                    "The webhook could not be processed; please retry");
        }
    }

    /**
     * Records the event id (short transaction). An existing row that was never completed is retryable; only a
     * unique-key violation on the insert (a concurrent delivery) is translated, anything else propagates.
     */
    private Claim claim(String eventId, String eventType, String rawBody) {
        try {
            return newTx.execute(s -> {
                WebhookEvent existing = webhookEvents.findByProviderEventId(eventId).orElse(null);
                if (existing != null) {
                    return new Claim(existing.getId(), existing.getProcessedAt() != null);
                }
                WebhookEvent event = new WebhookEvent();
                event.setProviderEventId(eventId);
                event.setEventType(eventType.length() > 60 ? eventType.substring(0, 60) : eventType);
                event.setPayload(rawBody);
                return new Claim(webhookEvents.saveAndFlush(event).getId(), false);
            });
        } catch (DataIntegrityViolationException e) {
            if (!SqlStates.UNIQUE_VIOLATION.equals(SqlStates.of(e))) {
                throw e;
            }
            WebhookEvent winner = newTx.execute(s -> webhookEvents.findByProviderEventId(eventId).orElseThrow());
            return new Claim(winner.getId(), winner.getProcessedAt() != null);
        }
    }

    private String apply(String eventType, JSONObject json) {
        return switch (eventType) {
            case "payment.captured" -> paymentCaptured(entity(json, "payment"));
            case "payment.failed" -> paymentFailed(entity(json, "payment"));
            case "refund.processed" -> refundUpdated(entity(json, "refund"), RefundStatus.PROCESSED);
            case "refund.failed" -> refundUpdated(entity(json, "refund"), RefundStatus.FAILED);
            default -> {
                log.info("Ignoring webhook event {}", eventType);
                yield IGNORED;
            }
        };
    }

    private String paymentCaptured(JSONObject payment) {
        String orderId = payment.optString("order_id", null);
        String paymentId = payment.optString("id", null);
        if (!StringUtils.hasText(orderId) || !StringUtils.hasText(paymentId) || !payments.existsByOrderId(orderId)) {
            log.warn("Ignoring payment.captured for unknown order {}", orderId);
            return IGNORED;
        }
        try {
            paymentService.confirmWithProvider(orderId, paymentId, payment.optString("method", null),
                    BookingActor.SYSTEM);
        } catch (ApiException e) {
            if (!"PAYMENT_VERIFICATION_FAILED".equals(e.getCode())) {
                throw e; // e.g. the provider was unreachable: let Razorpay deliver the event again
            }
            // Redelivering will not make a mismatching payment match, so acknowledge it (already logged as an error).
            return IGNORED;
        }
        return OK;
    }

    private String paymentFailed(JSONObject payment) {
        String orderId = payment.optString("order_id", null);
        String reason = payment.optString("error_description", "Payment failed");
        return tx.execute(s -> {
            Payment found = StringUtils.hasText(orderId) ? payments.findByOrderIdForUpdate(orderId).orElse(null) : null;
            if (found == null) {
                log.warn("Ignoring payment.failed for unknown order {}", orderId);
                return IGNORED;
            }
            // A failed attempt must never undo a payment that already went through on this order.
            if (found.getStatus() == PaymentStatus.CREATED || found.getStatus() == PaymentStatus.FAILED) {
                found.setStatus(PaymentStatus.FAILED);
                found.setFailureReason(reason.length() > MAX_FAILURE_REASON ? reason.substring(0, MAX_FAILURE_REASON) : reason);
            }
            return OK;
        });
    }

    private String refundUpdated(JSONObject refund, RefundStatus status) {
        String refundId = refund.optString("id", null);
        Long id = StringUtils.hasText(refundId) ? refunds.findByProviderRefundId(refundId).map(Refund::getId).orElse(null)
                : null;
        if (id == null) {
            log.warn("Ignoring refund event for unknown refund {}", refundId);
            return IGNORED;
        }
        if (status == RefundStatus.FAILED) {
            // The books must show the money as owed again (payment CAPTURED) so the refund job re-drives it.
            refundService.providerReportedFailure(id);
        } else {
            tx.executeWithoutResult(s -> refunds.findById(id).orElseThrow().setStatus(status));
        }
        return OK;
    }

    /** Header value, or the body hash when absent; ids that do not fit the column are replaced by their hash. */
    static String eventId(String header, String rawBody) {
        if (!StringUtils.hasText(header)) {
            return sha256Hex(rawBody);
        }
        String id = header.trim();
        return id.length() > MAX_EVENT_ID ? sha256Hex(id) : id;
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
