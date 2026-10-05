package com.smartparking.pricing;

import java.math.BigDecimal;

public record QuoteDto(
        PricingMode pricingMode,
        long durationMinutes,
        BigDecimal baseAmount,
        BigDecimal platformFee,
        BigDecimal gstAmount,
        BigDecimal totalAmount,
        String breakdown) {

    public static QuoteDto from(Quote q) {
        return new QuoteDto(
                q.pricingMode(),
                q.durationMinutes(),
                q.baseAmount(),
                q.platformFee(),
                q.gstAmount(),
                q.totalAmount(),
                q.breakdown());
    }
}
