package com.smartparking.search;

import java.time.Instant;
import java.util.List;

public record SearchResponse(
        List<SearchResultDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        Center center,
        double radiusKm,
        Window window) {

    public record Center(double lat, double lng) {
    }

    public record Window(Instant start, Instant end) {
    }
}
