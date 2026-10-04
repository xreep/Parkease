package com.smartparking.common.security;

import com.smartparking.user.Role;
import com.smartparking.user.User;

/** The authenticated principal, rebuilt from the JWT on every request. */
public record AuthUser(Long id, String email, Role role) {

    public static AuthUser from(User user) {
        return new AuthUser(user.getId(), user.getEmail(), user.getRole());
    }
}
