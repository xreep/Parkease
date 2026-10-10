package com.smartparking.settings;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Advisory hourly price range for the cities of one tier (1 = metro, 2 = other capitals, 3 = the rest). */
public record PriceGuidelineDto(@NotNull Integer tier, @NotNull BigDecimal minHourly, @NotNull BigDecimal maxHourly) {
}
