package com.smartparking.location.dto;

import com.smartparking.location.StateType;

public record StateDto(Long id, String name, String code, String slug, StateType type,
                       String capitalName, long cityCount) {
}
