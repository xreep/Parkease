package com.smartparking.payment;

import com.smartparking.common.security.JwtProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class PaymentConfig {

    private static final Logger log = LoggerFactory.getLogger(PaymentConfig.class);

    @Bean
    PaymentProvider paymentProvider(PaymentProperties properties, JwtProperties jwt) {
        PaymentProperties.Razorpay razorpay = properties.razorpayOrEmpty();
        if (StringUtils.hasText(razorpay.keyId()) && StringUtils.hasText(razorpay.keySecret())) {
            return new RazorpayPaymentProvider(razorpay.keyId(), razorpay.keySecret(), razorpay.webhookSecret());
        }
        log.warn("Razorpay keys not set — using the mock payment provider");
        return new MockPaymentProvider(jwt.secret());
    }
}
