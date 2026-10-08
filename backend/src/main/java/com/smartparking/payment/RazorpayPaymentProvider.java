package com.smartparking.payment;

import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.smartparking.common.error.ApiException;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

public class RazorpayPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(RazorpayPaymentProvider.class);

    private final String keyId;
    private final String keySecret;
    private final String webhookSecret;
    private final RazorpayClient client;

    /** Creating the SDK client does not contact Razorpay. */
    public RazorpayPaymentProvider(String keyId, String keySecret, String webhookSecret) {
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
    public ProviderRefund refund(String paymentId, long amountPaise, String reason) {
        try {
            JSONObject request = new JSONObject()
                    .put("amount", amountPaise)
                    .put("notes", new JSONObject().put("reason", reason == null ? "" : reason));
            JSONObject refund = client.payments.refund(paymentId, request).toJson();
            RefundStatus status = "processed".equals(refund.optString("status")) ? RefundStatus.PROCESSED
                    : RefundStatus.PENDING;
            return new ProviderRefund(refund.getString("id"), status);
        } catch (RazorpayException | JSONException e) {
            throw providerError("refund payment", e);
        }
    }

    @Override
    public boolean verifyWebhook(String rawBody, String signature) {
        if (!StringUtils.hasText(webhookSecret) || rawBody == null) {
            return false;
        }
        return Signatures.matches(Signatures.hmacSha256Hex(webhookSecret, rawBody), signature);
    }

    private static ApiException providerError(String action, Exception e) {
        log.error("Razorpay failed to {}: {}", action, e.getMessage());
        return new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER_ERROR",
                "Payment provider is unavailable. Please try again.");
    }
}
