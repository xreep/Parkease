package com.smartparking.availability.dto;

import java.util.List;

public record HoursDto(boolean open24x7, List<HoursRuleDto> rules) {
}
