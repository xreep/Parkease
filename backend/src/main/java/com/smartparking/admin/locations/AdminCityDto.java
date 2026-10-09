package com.smartparking.admin.locations;

public record AdminCityDto(Long id, Long stateId, String stateName, String name, String slug, double lat, double lng,
                           boolean capital, int tier, boolean active, long listingsCount) {
}
