package com.smartparking.payment;

import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.smartparking.common.error.ApiException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

public class RazorpayPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(RazorpayPaymentProvider.class);

    /** Razorpay ids are letters, digits and underscores; anything else must never be put into a URL path. */
    private static final Pattern PROVIDER_ID = Pattern.compile("[A-Za-z0-9_]{1,64}");

    private final String keyId;
    private final String keySecret;
    private final String webhookSecret;
    private final RazorpayClient client;
    private final String apiBase;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** Creating the SDK client does not contact Razorpay. */
    public RazorpayPaymentProvider(String keyId, String keySecret, String webhookSecret) {
        this(keyId, keySecret, webhookSecret, "https://api.razorpay.com/v1");
    }

    /** {@code apiBase} lets tests point the refund call at a local server. */
    RazorpayPaymentProvider(String keyId, String keySecret, String webhookSecret, String apiBase) {
        this.apiBase = apiBase;
        this.keyId = keyId;
        this.keySecret = keySecret;
        this.webhookSecret = webhookSecret;
        try {
            this.client = new RazorpayClient(keyId, keySecret);
        } catch (RazorpayException e) {
            throw new IllegalStateException("Could not initialise the Razorpay client", e);
        }
    }

    @Override
    public PaymentProviderType type() {
        return PaymentProviderType.RAZORPAY;
    }

    @Override
    public ProviderOrder createOrder(String receipt, long amountPaise, Map<String, String> notes) {
        try {
            JSONObject request = new JSONObject()
                    .put("amount", amountPaise)
                    .put("currency", "INR")
                    .put("receipt", receipt)
                    .put("notes", new JSONObject(notes));
            JSONObject order = client.orders.create(request).toJson();
            return new ProviderOrder(order.getString("id"), order.getLong("amount"), order.getString("currency"), keyId);
        } catch (RazorpayException | JSONException e) {
            throw providerError("create order", e);
        }
    }

    @Override
    public boolean verifyPayment(String orderId, String paymentId, String signature) {
        if (orderId == null || paymentId == null) {
            return false;
        }
        return Signatures.matches(Signatures.hmacSha256Hex(keySecret, orderId + "|" + paymentId), signature);
    }

    @Override
    public ProviderPayment fetchPayment(String paymentId) {
        try {
            return toProviderPayment(client.payments.fetch(paymentId).toJson());
        } catch (RazorpayException | JSONException e) {
            throw providerError("fetch payment", e);
        }
    }

    @Override
    public void capture(String paymentId, long amountPaise, String currency) {
        try {
            client.payments.capture(paymentId, new JSONObject().put("amount", amountPaise).put("currency", currency));
        } catch (RazorpayException | JSONException e) {
            throw providerError("capture payment", e);
        }
    }

    @Override
    public List<ProviderPayment> fetchOrderPayments(String orderId) {
        try {
            return client.orders.fetchPayments(orderId).stream().map(p -> toProviderPayment(p.toJson())).toList();
        } catch (RazorpayException | JSONException e) {
            throw providerError("fetch the payments of an order", e);
        }
    }

    static ProviderPayment toProviderPayment(JSONObject payment) {
        return new ProviderPayment(payment.getString("id"), payment.getString("status"),
                optText(payment, "order_id"), payment.getLong("amount"), payment.getString("currency"),
                optText(payment, "method"), optText(payment, "error_description"));
    }

    /** The text of a field, or null when it is missing or JSON null (org.json would answer "null"). */
    private static String optText(JSONObject json, String key) {
        return json.isNull(key) ? null : json.optString(key, null);
    }

    @Override
    public ProviderRefund refund(String paymentId, long amountPaise, String reason) {
        return refund(paymentId, amountPaise, reason, null, null);
    }

    @Override
    public ProviderRefund refund(String paymentId, long amountPaise, String reason, String idempotencyKey,
                                 String receipt) {
        return refund(paymentId, amountPaise, reason, idempotencyKey, receipt, Map.of());
    }

    /**
     * Razorpay's idempotency header ({@code X-Refund-Idempotency}) is not exposed by the Java SDK (it cannot add
     * request headers), so the refund call is made directly: {@code POST /v1/payments/{id}/refund} with basic auth.
     */
    @Override
    public ProviderRefund refund(String paymentId, long amountPaise, String reason, String idempotencyKey,
                                 String receipt, Map<String, String> extraNotes) {
        if (paymentId == null || !PROVIDER_ID.matcher(paymentId).matches()) {
            throw providerError("refund payment", new IllegalArgumentException("Invalid payment id"));
        }
        try {
            JSONObject notes = new JSONObject();
            for (Map.Entry<String, String> note : extraNotes.entrySet()) {
                notes.put(note.getKey(), note.getValue());
            }
            notes.put("reason", reason == null ? "" : reason);
            JSONObject payload = new JSONObject().put("amount", amountPaise);
            if (receipt != null) {
                payload.put("receipt", receipt);
                notes.put("parkeaseRefund", receipt); // echoed back in fetchRefunds, even where receipts are not
            }
            String body = payload.put("notes", notes).toString();
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(apiBase + "/payments/" + paymentId + "/refund"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Basic " + Base64.getEncoder()
                            .encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (idempotencyKey != null) {
                request.header("X-Refund-Idempotency", idempotencyKey);
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw providerError("refund payment", new IOException("Razorpay answered " + response.statusCode()),
                        errorDescription(response.body()));
            }
            return toProviderRefund(new JSONObject(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw providerError("refund payment", e);
        } catch (IOException | JSONException e) {
            throw providerError("refund payment", e);
        }
    }

    @Override
    public List<ProviderRefund> fetchRefunds(String paymentId) {
        try {
            return client.payments.fetchAllRefunds(paymentId).stream().map(r -> toProviderRefund(r.toJson())).toList();
        } catch (RazorpayException | JSONException e) {
            throw providerError("fetch the refunds of a payment", e);
        }
    }

    static ProviderRefund toProviderRefund(JSONObject refund) {
        String status = refund.optString("status");
        Map<String, String> notes = new java.util.HashMap<>();
        JSONObject sent = refund.optJSONObject("notes"); // an empty array when the refund has none
        if (sent != null) {
            for (java.util.Iterator<String> keys = sent.keys(); keys.hasNext();) {
                String key = keys.next();
                notes.put(key, sent.optString(key));
            }
        }
        return new ProviderRefund(refund.getString("id"), "processed".equals(status) ? RefundStatus.PROCESSED
                : "failed".equals(status) ? RefundStatus.FAILED : RefundStatus.PENDING,
                refund.has("amount") && !refund.isNull("amount") ? refund.getLong("amount") : null,
                optText(refund, "receipt"), Map.copyOf(notes));
    }

    @Override
    public boolean verifyWebhook(String rawBody, String signature) {
        if (!StringUtils.hasText(webhookSecret) || rawBody == null) {
            return false;
        }
        return Signatures.matches(Signatures.hmacSha256Hex(webhookSecret, rawBody), signature);
    }

    private static ApiException providerError(String action, Exception e) {
        return providerError(action, e, e.getMessage());
    }

    private static ApiException providerError(String action, Exception e, String description) {
        log.error("Razorpay failed to {}: {}", action, description, e);
        return new PaymentProviderException(description);
    }

    /** {@code error.description} of a Razorpay error body, or the start of the body if it is not that shape. */
    private static String errorDescription(String body) {
        try {
            JSONObject error = new JSONObject(body).optJSONObject("error");
            if (error != null && !error.optString("description").isBlank()) {
                return error.getString("description");
            }
        } catch (JSONException ignored) {
            // not JSON: fall through
        }
        return body == null ? null : body.substring(0, Math.min(body.length(), 200));
    }
}
