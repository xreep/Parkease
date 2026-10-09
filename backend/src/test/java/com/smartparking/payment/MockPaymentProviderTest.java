package com.smartparking.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MockPaymentProviderTest {

    private final MockPaymentProvider provider = new MockPaymentProvider("jwt-secret-for-tests");

    @Test
    void createsOrdersWithMockIdsAndNoKey() {
        ProviderOrder order = provider.createOrder("PE00000001", 6708, Map.of("bookingId", "1"));

        assertThat(order.orderId()).matches("order_mock_[0-9a-f]{16}");
        assertThat(order.amountPaise()).isEqualTo(6708);
        assertThat(order.currency()).isEqualTo("INR");
        assertThat(order.keyId()).isNull();
        assertThat(provider.type()).isEqualTo(PaymentProviderType.MOCK);
        assertThat(provider.createOrder("PE00000001", 6708, Map.of()).orderId()).isNotEqualTo(order.orderId());
    }

    @Test
    void verifiesOwnSignaturesAndRejectsTamperedOnes() {
        String signature = provider.signForTesting("order_mock_1", "pay_mock_1");

        assertThat(provider.verifyPayment("order_mock_1", "pay_mock_1", signature)).isTrue();
        assertThat(provider.verifyPayment("order_mock_2", "pay_mock_1", signature)).isFalse();
        assertThat(provider.verifyPayment("order_mock_1", "pay_mock_2", signature)).isFalse();
        assertThat(provider.verifyPayment("order_mock_1", "pay_mock_1", signature.replace(signature.charAt(0), 'z')))
                .isFalse();
        assertThat(provider.verifyPayment("order_mock_1", "pay_mock_1", null)).isFalse();
        assertThat(provider.verifyPayment(null, "pay_mock_1", signature)).isFalse();
    }

    @Test
    void signaturesDependOnTheJwtSecret() {
        MockPaymentProvider other = new MockPaymentProvider("another-secret");

        assertThat(other.verifyPayment("o", "p", provider.signForTesting("o", "p"))).isFalse();
        assertThat(provider.signForTesting("o", "p")).isEqualTo(new MockPaymentProvider("jwt-secret-for-tests")
                .signForTesting("o", "p"));
    }

    @Test
    void usesTheDerivedSecretRule() {
        String derived = Signatures.hmacSha256Hex(
                sha256Hex("mock-payments:jwt-secret-for-tests"), "order_mock_1|pay_mock_1");

        assertThat(provider.signForTesting("order_mock_1", "pay_mock_1")).isEqualTo(derived);
    }

    @Test
    void refundsAreImmediatelyProcessed() {
        ProviderRefund refund = provider.refund("pay_mock_1", 3000, "cancelled");

        assertThat(refund.refundId()).matches("rfnd_mock_[0-9a-f]{16}");
        assertThat(refund.status()).isEqualTo(RefundStatus.PROCESSED);
    }

    @Test
    void webhooksAreNeverVerified() {
        assertThat(provider.verifyWebhook("{}", Signatures.hmacSha256Hex("x", "{}"))).isFalse();
    }

    private static String sha256Hex(String v) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(v.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
