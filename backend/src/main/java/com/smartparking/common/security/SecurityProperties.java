package com.smartparking.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Edge protection settings ({@code app.security.*}).
 *
 * @param hsts              send {@code Strict-Transport-Security} (production, where TLS is terminated by the host)
 * @param rateLimit         per-IP, per-minute budgets for the public routes
 * @param maxJsonBodyBytes  largest accepted non-multipart request body
 */
@ConfigurationProperties("app.security")
public record SecurityProperties(
        @DefaultValue("false") boolean hsts,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue("1048576") long maxJsonBodyBytes) {

    public SecurityProperties {
        if (rateLimit == null) {
            rateLimit = new RateLimit(120, 20, 30);
        }
    }

    /** Requests per minute per client IP. Spec section 23: public reads 120, uploads and disputes 20, verify 30. */
    public record RateLimit(
            @DefaultValue("120") int publicPerMinute,
            @DefaultValue("20") int uploadPerMinute,
            @DefaultValue("30") int paymentVerifyPerMinute) {
    }
}
