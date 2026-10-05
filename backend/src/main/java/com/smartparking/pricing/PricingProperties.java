package com.smartparking.pricing;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.pricing")
public record PricingProperties(
        @DefaultValue("10") BigDecimal platformFeePercent, @DefaultValue("18") BigDecimal gstPercent) {
}
