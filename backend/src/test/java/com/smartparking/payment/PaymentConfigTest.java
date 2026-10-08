package com.smartparking.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.common.security.JwtProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PaymentConfigTest {

    private final PaymentConfig config = new PaymentConfig();
    private final JwtProperties jwt = new JwtProperties("c2VjcmV0", Duration.ofMinutes(15), Duration.ofDays(7));

    private PaymentProvider provider(String keyId, String keySecret) {
        return config.paymentProvider(
                new PaymentProperties(new PaymentProperties.Razorpay(keyId, keySecret, "whsec")), jwt);
    }

    @Test
    void withoutKeysTheMockProviderIsUsed() {
        assertThat(provider("", "")).isInstanceOf(MockPaymentProvider.class);
        assertThat(provider(null, null)).isInstanceOf(MockPaymentProvider.class);
        assertThat(config.paymentProvider(new PaymentProperties(null), jwt)).isInstanceOf(MockPaymentProvider.class);
    }

    @Test
    void bothKeysAreRequiredForRazorpay() {
        assertThat(provider("rzp_test_x", "")).isInstanceOf(MockPaymentProvider.class);
        assertThat(provider("", "secret")).isInstanceOf(MockPaymentProvider.class);
        assertThat(provider("  ", "secret")).isInstanceOf(MockPaymentProvider.class);
    }

    @Test
    void withKeysTheRazorpayProviderIsUsed() {
        PaymentProvider provider = provider("rzp_test_x", "secret");

        assertThat(provider).isInstanceOf(RazorpayPaymentProvider.class);
        assertThat(provider.type()).isEqualTo(PaymentProviderType.RAZORPAY);
    }
}
