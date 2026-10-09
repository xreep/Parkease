package com.smartparking.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartparking.common.security.JwtProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PaymentConfigTest {

    private final PaymentConfig config = new PaymentConfig();
    private final JwtProperties jwt = new JwtProperties("c2VjcmV0", Duration.ofMinutes(15), Duration.ofDays(7));

    private static MockEnvironment env(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    private PaymentProvider provider(String keyId, String keySecret) {
        return config.paymentProvider(
                new PaymentProperties(new PaymentProperties.Razorpay(keyId, keySecret, "whsec"), true), jwt, env());
    }

    private PaymentProvider provider(String keyId, String keySecret, boolean mockEnabled, String... profiles) {
        return config.paymentProvider(
                new PaymentProperties(new PaymentProperties.Razorpay(keyId, keySecret, "whsec"), mockEnabled), jwt,
                env(profiles));
    }

    @Test
    void withoutKeysTheMockProviderIsUsed() {
        assertThat(provider("", "")).isInstanceOf(MockPaymentProvider.class);
        assertThat(provider(null, null)).isInstanceOf(MockPaymentProvider.class);
        assertThat(config.paymentProvider(new PaymentProperties(null, true), jwt, env()))
                .isInstanceOf(MockPaymentProvider.class);
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

    @Test
    void productionRefusesToStartWithoutRazorpayKeys() {
        for (String[] keys : new String[][] {{"", ""}, {null, null}, {"rzp_live_x", ""}, {"", "secret"}, {"  ", "  "}}) {
            assertThatThrownBy(() -> provider(keys[0], keys[1], true, "prod"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET must be set in production");
        }
        assertThatThrownBy(() -> config.paymentProvider(new PaymentProperties(null, false), jwt, env("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET must be set in production");
        // Production is detected among several active profiles.
        assertThatThrownBy(() -> provider("", "", true, "dev", "prod")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void productionWithKeysUsesRazorpayEvenIfTheMockFlagIsOn() {
        assertThat(provider("rzp_live_x", "secret", true, "prod")).isInstanceOf(RazorpayPaymentProvider.class);
    }

    @Test
    void theMockProviderNeedsTheMockFlagWhenNoKeysAreSet() {
        assertThatThrownBy(() -> provider("", "", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.payments.mock-enabled");
        assertThat(provider("rzp_test_x", "secret", false)).isInstanceOf(RazorpayPaymentProvider.class);
    }
}
