package com.smartparking.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;

/**
 * Offline provider used when no Razorpay keys are configured. Signatures follow the same HMAC rule as Razorpay,
 * keyed with a secret derived from the JWT secret, so the server-side verification path is exercised identically.
 */
public class MockPaymentProvider implements PaymentProvider {

    private final SecureRandom random = new SecureRandom();
    private final String secret;

    public MockPaymentProvider(String jwtSecret) {
        this.secret = sha256Hex("mock-payments:" + jwtSecret);
    }

    @Override
    public PaymentProviderType type() {
        return PaymentProviderType.MOCK;
    }

    @Override
    public ProviderOrder createOrder(String receipt, long amountPaise, Map<String, String> notes) {
        return new ProviderOrder("order_mock_" + randomHex(8), amountPaise, "INR", null);
    }

    @Override
    public boolean verifyPayment(String orderId, String paymentId, String signature) {
        if (orderId == null || paymentId == null) {
            return false;
        }
        return Signatures.matches(signForTesting(orderId, paymentId), signature);
    }

    /** The signature a genuine checkout would return; used by the mock pay endpoint and tests. */
    public String signForTesting(String orderId, String paymentId) {
        return Signatures.hmacSha256Hex(secret, orderId + "|" + paymentId);
    }

    @Override
    public ProviderRefund refund(String paymentId, long amountPaise, String reason) {
        return new ProviderRefund("rfnd_mock_" + randomHex(8), RefundStatus.PROCESSED);
    }

    @Override
    public boolean verifyWebhook(String rawBody, String signature) {
        return false;
    }

    private String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        random.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
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
