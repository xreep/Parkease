package com.smartparking.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartparking.common.error.ApiException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Offline checks only: signature rules; order and refund calls need the Razorpay network. */
class RazorpayPaymentProviderTest {

    private final RazorpayPaymentProvider provider =
            new RazorpayPaymentProvider("rzp_test_key", "secret", "whsec");

    @Test
    void verifiesPaymentSignatureWithTheKeySecret() {
        assertThat(provider.type()).isEqualTo(PaymentProviderType.RAZORPAY);
        assertThat(provider.verifyPayment("order_123", "pay_456",
                "18bfc0baafae8f6367711ee362f2201aaa3654274683100e5367bb9a2bd29cbe")).isTrue();
        assertThat(provider.verifyPayment("order_123", "pay_457",
                "18bfc0baafae8f6367711ee362f2201aaa3654274683100e5367bb9a2bd29cbe")).isFalse();
        assertThat(provider.verifyPayment("order_123", "pay_456", null)).isFalse();
    }

    @Test
    void verifiesWebhookSignatureWithTheWebhookSecret() {
        String body = "{\"event\":\"payment.captured\"}";
        String signature = "4673dd707ef4c41b987cb7fefe1583142dc702388c93145b7814b9ad3d3c183e";

        assertThat(provider.verifyWebhook(body, signature)).isTrue();
        assertThat(provider.verifyWebhook(body + " ", signature)).isFalse();
        assertThat(provider.verifyWebhook(body, null)).isFalse();
    }

    @Test
    void webhookIsRejectedWithoutAWebhookSecret() {
        RazorpayPaymentProvider noWebhook = new RazorpayPaymentProvider("rzp_test_key", "secret", "");

        assertThat(noWebhook.verifyWebhook("{}", Signatures.hmacSha256Hex("k", "{}"))).isFalse();
        assertThat(new RazorpayPaymentProvider("rzp_test_key", "secret", null).verifyWebhook("{}", "x")).isFalse();
    }

    @Test
    void mapsAProviderPaymentFromRazorpaysJson() throws org.json.JSONException {
        ProviderPayment payment = RazorpayPaymentProvider.toProviderPayment(new org.json.JSONObject(
                "{\"id\":\"pay_1\",\"status\":\"authorized\",\"order_id\":\"order_1\",\"amount\":6708,"
                        + "\"currency\":\"INR\",\"method\":\"upi\"}"));

        assertThat(payment).isEqualTo(new ProviderPayment("pay_1", "authorized", "order_1", 6708L, "INR", "upi"));
        ProviderPayment bare = RazorpayPaymentProvider.toProviderPayment(new org.json.JSONObject(
                "{\"id\":\"pay_2\",\"status\":\"created\",\"order_id\":null,\"amount\":100,\"currency\":\"INR\"}"));
        assertThat(bare.orderId()).isNull();
        assertThat(bare.method()).isNull();
    }

    /** What the fake Razorpay saw of the last request. */
    private record Seen(String method, String path, String authorization, String idempotency, String body) {
    }

    private static RazorpayPaymentProvider providerAgainst(HttpServer server) {
        return new RazorpayPaymentProvider("rzp_test_key", "secret", "whsec",
                "http://localhost:" + server.getAddress().getPort() + "/v1");
    }

    private static HttpServer razorpayAnswering(int status, String json, AtomicReference<Seen> seen) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.set(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("X-Refund-Idempotency"), body));
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    void refundIsSentWithTheIdempotencyHeaderAndBasicAuth() throws Exception {
        AtomicReference<Seen> seen = new AtomicReference<>();
        HttpServer server = razorpayAnswering(200, "{\"id\":\"rfnd_1\",\"status\":\"processed\"}", seen);
        try {
            ProviderRefund refund = providerAgainst(server).refund("pay_9", 6708L, "Hold lapsed", "parkease-refund-42");

            assertThat(refund).isEqualTo(new ProviderRefund("rfnd_1", RefundStatus.PROCESSED));
            Seen request = seen.get();
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/v1/payments/pay_9/refund");
            assertThat(request.idempotency()).isEqualTo("parkease-refund-42");
            assertThat(request.authorization()).isEqualTo("Basic "
                    + java.util.Base64.getEncoder().encodeToString("rzp_test_key:secret".getBytes(StandardCharsets.UTF_8)));
            org.json.JSONObject body = new org.json.JSONObject(request.body());
            assertThat(body.getLong("amount")).isEqualTo(6708L);
            assertThat(body.getJSONObject("notes").getString("reason")).isEqualTo("Hold lapsed");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aRefundWithoutAKeySendsNoIdempotencyHeaderAndPendingStatusesAreKept() throws Exception {
        AtomicReference<Seen> seen = new AtomicReference<>();
        HttpServer server = razorpayAnswering(200, "{\"id\":\"rfnd_2\",\"status\":\"pending\"}", seen);
        try {
            ProviderRefund refund = providerAgainst(server).refund("pay_9", 100L, null);

            assertThat(refund).isEqualTo(new ProviderRefund("rfnd_2", RefundStatus.PENDING));
            assertThat(seen.get().idempotency()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aRefusedRefundIsAProviderErrorAndAMalformedPaymentIdNeverReachesTheNetwork() throws Exception {
        AtomicReference<Seen> seen = new AtomicReference<>();
        HttpServer server = razorpayAnswering(400,
                "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"already refunded\"}}", seen);
        try {
            RazorpayPaymentProvider provider = providerAgainst(server);

            assertThatThrownBy(() -> provider.refund("pay_9", 100L, "x", "k"))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getCode()).isEqualTo("PAYMENT_PROVIDER_ERROR"));
            seen.set(null);
            assertThatThrownBy(() -> provider.refund("pay_9/../../orders", 100L, "x", "k"))
                    .isInstanceOf(ApiException.class);
            assertThat(seen.get()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsProviderRefundStatuses() throws org.json.JSONException {
        assertThat(RazorpayPaymentProvider.toProviderRefund(new org.json.JSONObject("{\"id\":\"r1\",\"status\":\"processed\"}")))
                .isEqualTo(new ProviderRefund("r1", RefundStatus.PROCESSED));
        assertThat(RazorpayPaymentProvider.toProviderRefund(new org.json.JSONObject("{\"id\":\"r2\",\"status\":\"failed\"}")))
                .isEqualTo(new ProviderRefund("r2", RefundStatus.FAILED));
        assertThat(RazorpayPaymentProvider.toProviderRefund(new org.json.JSONObject("{\"id\":\"r3\",\"status\":\"pending\"}")))
                .isEqualTo(new ProviderRefund("r3", RefundStatus.PENDING));
    }
}
