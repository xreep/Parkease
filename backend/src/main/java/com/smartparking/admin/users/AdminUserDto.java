package com.smartparking.admin.users;

import com.smartparking.user.Role;
import com.smartparking.user.User;
import com.smartparking.user.UserStatus;
import java.time.Instant;

/**
 * A user as admins see them. Accounts store one display name; {@code firstName} is its first word and
 * {@code lastName} the rest, and {@code name} is the whole thing.
 */
public record AdminUserDto(Long id, String name, String firstName, String lastName, String email, String phone,
                           Role role, UserStatus status, boolean emailVerified, Instant createdAt,
                           long bookingsCount, long listingsCount) {

    public static AdminUserDto from(User u, long bookingsCount, long listingsCount) {
        String name = u.getName() == null ? "" : u.getName().trim();
        int space = name.indexOf(' ');
        String first = space < 0 ? name : name.substring(0, space);
        String last = space < 0 ? "" : name.substring(space + 1).trim();
        return new AdminUserDto(u.getId(), name, first, last, u.getEmail(), u.getPhone(), u.getRole(), u.getStatus(),
                u.isEmailVerified(), u.getCreatedAt(), bookingsCount, listingsCount);
    }
}
