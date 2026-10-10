package com.smartparking.support;

import com.smartparking.common.security.JwtProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Swaps the payment provider for a {@link RecordingPaymentProvider}; {@code @Import} it from the test class. */
@TestConfiguration
public class RecordingProviderTestConfig {

    @Bean
    @Primary
    RecordingPaymentProvider recordingProvider(JwtProperties jwt) {
        return new RecordingPaymentProvider(jwt.secret());
    }
}
