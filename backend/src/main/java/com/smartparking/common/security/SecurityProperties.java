package com.smartparking.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Edge protection settings ({@code app.security.*}).
 *
 * @param trustForwardedFor whether {@code X-Forwarded-For} names the client (true only behind a proxy we control,
 *                          e.g. Render; otherwise any caller could pick its own rate-limit bucket)
 * @param trustedProxyHops  how many proxies append to {@code X-Forwarded-For} in front of the app; the client is the
 *                          entry that many places from the right
 * @param hsts              send {@code Strict-Transport-Security} (production, where TLS is terminated by the host)
 * @param rateLimit         per-IP, per-minute budgets for the public routes
 * @param maxJsonBodyBytes  largest accepted non-multipart request body
 */
@ConfigurationProperties("app.security")
public record SecurityProperties(
        @DefaultValue("false") boolean trustForwardedFor,
        @DefaultValue("1") int trustedProxyHops,
        @DefaultValue("false") boolean hsts,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue("1048576") long maxJsonBodyBytes) {

    public SecurityProperties {
        if (rateLimit == null) {
            rateLimit = new RateLimit(120, 20, 30);
        }
        if (trustedProxyHops < 1) {
            trustedProxyHops = 1;
        }
    }

    /** Requests per minute per client IP. Spec section 23: public reads 120, uploads and disputes 20, verify 30. */
    public record RateLimit(
            @DefaultValue("120") int publicPerMinute,
            @DefaultValue("20") int uploadPerMinute,
            @DefaultValue("30") int paymentVerifyPerMinute) {
    }
}
