package com.smartparking.search;

import com.smartparking.common.error.ApiException;

public enum SearchSort {
    DISTANCE, PRICE, RATING;

    /** Parses the {@code sort} query parameter (case-insensitive); null or blank means distance. */
    public static SearchSort parse(String value) {
        if (value == null || value.isBlank()) {
            return DISTANCE;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_PARAMETER", "Invalid value for parameter 'sort'");
        }
    }
}
