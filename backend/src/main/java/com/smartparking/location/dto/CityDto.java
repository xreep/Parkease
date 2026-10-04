package com.smartparking.location.dto;

import com.smartparking.location.City;

public record CityDto(Long id, String name, String slug, double lat, double lng, boolean capital,
                      String stateName, String stateCode, String stateSlug) {

    public static CityDto from(City city) {
        return new CityDto(city.getId(), city.getName(), city.getSlug(), city.getLat(), city.getLng(),
                city.isCapital(), city.getState().getName(), city.getState().getCode(), city.getState().getSlug());
    }
}
