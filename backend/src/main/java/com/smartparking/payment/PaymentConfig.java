package com.smartparking.payment;

import com.smartparking.common.security.JwtProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.util.StringUtils;

@Configuration
public class PaymentConfig {

    private static final Logger log = LoggerFactory.getLogger(PaymentConfig.class);

    /**
     * Razorpay when both keys are set. Without them: startup fails in production (a live deployment must never fall
     * back to a provider that takes no money) and whenever the mock is switched off; otherwise the mock is used.
     */
    @Bean
    PaymentProvider paymentProvider(PaymentProperties properties, JwtProperties jwt, Environment environment) {
        PaymentProperties.Razorpay razorpay = properties.razorpayOrEmpty();
        if (StringUtils.hasText(razorpay.keyId()) && StringUtils.hasText(razorpay.keySecret())) {
            return new RazorpayPaymentProvider(razorpay.keyId(), razorpay.keySecret(), razorpay.webhookSecret());
        }
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET must be set in production");
        }
        if (!properties.mockEnabled()) {
            throw new IllegalStateException("No payment provider: set RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET, "
                    + "or enable the mock provider with app.payments.mock-enabled (PAYMENTS_MOCK_ENABLED)");
        }
        log.warn("Razorpay keys not set — using the mock payment provider");
        return new MockPaymentProvider(jwt.secret());
    }
}
