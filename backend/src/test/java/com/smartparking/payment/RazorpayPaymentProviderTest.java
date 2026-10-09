package com.smartparking.payment;

import static org.assertj.core.api.Assertions.assertThat;

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
}
