package com.smartparking.pricing;

import java.math.BigDecimal;

public record Quote(
        PricingMode pricingMode,
        long durationMinutes,
        BigDecimal baseAmount,
        BigDecimal platformFee,
        BigDecimal gstAmount,
        BigDecimal totalAmount,
        String breakdown) {
}
