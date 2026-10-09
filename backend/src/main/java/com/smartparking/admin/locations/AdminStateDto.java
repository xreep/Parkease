package com.smartparking.admin.locations;

import com.smartparking.location.StateType;

public record AdminStateDto(Long id, String name, String code, String slug, StateType type, String capitalName,
                            long citiesCount) {
}
