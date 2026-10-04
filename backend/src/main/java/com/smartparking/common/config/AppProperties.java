package com.smartparking.common.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record AppProperties(
        String frontendUrl,
        List<String> corsAllowedOrigins,
        int bcryptStrength,
        int authRateLimitPerMinute) {
}
