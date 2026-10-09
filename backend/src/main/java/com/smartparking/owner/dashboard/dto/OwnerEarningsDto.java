package com.smartparking.owner.dashboard.dto;

import com.smartparking.common.web.PageResponse;
import java.math.BigDecimal;

/** {@code totals} cover all of the owner's earnings, whatever the filters and page. */
public record OwnerEarningsDto(Totals totals, PageResponse<OwnerEarningDto> earnings) {

    public record Totals(BigDecimal held, BigDecimal pendingPayout, BigDecimal paid, long reversedCount) {
    }
}
