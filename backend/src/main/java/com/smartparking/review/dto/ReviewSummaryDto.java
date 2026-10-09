package com.smartparking.review.dto;

import java.math.BigDecimal;
import java.util.Map;

/** {@code avgRating} has one decimal; {@code distribution} always has the keys "1" to "5". */
public record ReviewSummaryDto(BigDecimal avgRating, int reviewCount, Map<String, Integer> distribution) {
}
