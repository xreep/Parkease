package com.smartparking.settings;

import java.math.BigDecimal;

/** The advisory hourly price range for a city: its tier and that tier's min / max. */
public record CityPriceGuidelineDto(int tier, BigDecimal minHourly, BigDecimal maxHourly) {
}
