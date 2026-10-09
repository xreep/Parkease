package com.smartparking.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/** The admin-editable platform settings, as read and written by {@code /admin/settings}. */
public record PlatformSettingsDto(
        @NotNull BigDecimal platformFeePercent,
        @NotNull BigDecimal gstPercent,
        @NotNull Integer holdMinutes,
        @NotNull Integer approvalHours,
        @NotNull Integer requestMinLeadMinutes,
        @NotNull @Valid List<@NotNull @Valid PriceGuidelineDto> priceGuidelines) {
}
