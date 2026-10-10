package com.smartparking.common.security;

import com.smartparking.common.error.ApiException;
import com.smartparking.user.Role;

/** Explicit role guards that fail with our own error codes (rather than a bare 403 from the filter chain). */
public final class Roles {

    private Roles() {
    }

    /** Throws 403 {@code DRIVERS_ONLY} unless the principal is a driver. */
    public static void requireDriver(AuthUser principal) {
        if (principal == null || principal.role() != Role.DRIVER) {
            throw ApiException.forbidden("DRIVERS_ONLY", "Only drivers can do this");
        }
    }

    /** Throws 403 {@code FORBIDDEN} unless the principal is an owner or an admin. */
    public static void requireOwnerOrAdmin(AuthUser principal) {
        if (principal == null || (principal.role() != Role.OWNER && principal.role() != Role.ADMIN)) {
            throw ApiException.forbidden("FORBIDDEN", "Only owners and admins can do this");
        }
    }
}
