package com.smartparking.listing.dto;

import com.smartparking.pricing.QuoteDto;

/** {@code reason} is null when available, else CLOSED, BLOCKED, NO_VEHICLE_SLOTS or FULLY_BOOKED. */
public record ListingQuoteResponse(boolean available, String reason, int freeSlots, int totalSlots, QuoteDto quote) {
}
