package com.smartparking.support;

import com.smartparking.user.Role;
import com.smartparking.user.User;

public final class TestUsers {

    private TestUsers() {
    }

    public static User newUser(String email, Role role) {
        User user = new User();
        user.setName("Asha Test");
        user.setEmail(email);
        user.setPasswordHash("not-a-real-hash");
        user.setRole(role);
        return user;
    }
}
