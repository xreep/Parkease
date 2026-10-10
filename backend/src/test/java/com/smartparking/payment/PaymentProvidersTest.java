package com.smartparking.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Which provider handles which payment: new checkouts use the primary, existing payments their own. */
class PaymentProvidersTest {

    private final PaymentProvider razorpay = new RazorpayPaymentProvider("rzp_test_x", "secret", "whsec");
    private final PaymentProvider mock = new MockPaymentProvider("jwt-secret-for-tests");

    private static Payment paymentOf(PaymentProviderType type) {
        Payment payment = new Payment();
        payment.setProvider(type);
        return payment;
    }

    @Test
    void aProductionLikeSetupKeepsRazorpayForCheckoutsAndRoutesMockPaymentsToTheLegacyMock() {
        PaymentProviders providers = new PaymentProviders(razorpay, List.of(mock));

        assertThat(providers.primary()).isSameAs(razorpay);
        assertThat(providers.forPayment(paymentOf(PaymentProviderType.RAZORPAY))).isSameAs(razorpay);
        assertThat(providers.forPayment(paymentOf(PaymentProviderType.MOCK))).isSameAs(mock);
        assertThat(providers.forType(PaymentProviderType.MOCK)).isSameAs(mock);
    }

    @Test
    void withoutLegacyProvidersOnlyThePrimaryIsKnown() {
        PaymentProviders providers = new PaymentProviders(razorpay, List.of());

        assertThat(providers.forType(PaymentProviderType.RAZORPAY)).isSameAs(razorpay);
        assertThatThrownBy(() -> providers.forType(PaymentProviderType.MOCK))
                .isInstanceOf(PaymentProviderException.class);
    }

    @Test
    void aLegacyProviderOfThePrimarysTypeNeverReplacesIt() {
        PaymentProvider otherMock = new MockPaymentProvider("another-secret");
        PaymentProviders providers = new PaymentProviders(mock, List.of(otherMock));

        assertThat(providers.forType(PaymentProviderType.MOCK)).isSameAs(mock);
    }

    @Test
    void theDemoConfigurationRegistersTheMockOnlyBesideARealPrimary() throws Exception {
        PaymentConfig config = new PaymentConfig();
        com.smartparking.common.security.JwtProperties jwt = new com.smartparking.common.security.JwtProperties(
                "c2VjcmV0", java.time.Duration.ofMinutes(15), java.time.Duration.ofDays(7));

        assertThat(config.demoLegacyProviders(razorpay, jwt).providers())
                .singleElement().isInstanceOf(MockPaymentProvider.class);
        assertThat(config.demoLegacyProviders(mock, jwt).providers()).isEmpty();
        // Only the demo profile registers it; production without the demo profile never knows the mock.
        org.springframework.context.annotation.Profile profile = PaymentConfig.class.getDeclaredMethod(
                "demoLegacyProviders", PaymentProvider.class, com.smartparking.common.security.JwtProperties.class)
                .getAnnotation(org.springframework.context.annotation.Profile.class);
        assertThat(profile.value()).containsExactly("demo");
    }
}
