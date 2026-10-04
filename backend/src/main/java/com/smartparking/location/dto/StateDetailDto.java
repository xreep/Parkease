package com.smartparking.location.dto;

import com.smartparking.location.StateType;
import java.util.List;

public record StateDetailDto(Long id, String name, String code, String slug, StateType type,
                             String capitalName, List<CityDto> cities) {
}
