package com.smartparking.auth.dto;

import com.smartparking.user.UserDto;

public record AuthResponse(String accessToken, String refreshToken, long expiresIn, UserDto user) {
}
