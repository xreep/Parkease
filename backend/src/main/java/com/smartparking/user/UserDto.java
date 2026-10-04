package com.smartparking.user;

public record UserDto(
        Long id,
        String name,
        String email,
        String phone,
        Role role,
        boolean emailVerified,
        String avatarUrl) {

    public static UserDto from(User user) {
        return new UserDto(user.getId(), user.getName(), user.getEmail(), user.getPhone(),
                user.getRole(), user.isEmailVerified(), user.getAvatarUrl());
    }
}
