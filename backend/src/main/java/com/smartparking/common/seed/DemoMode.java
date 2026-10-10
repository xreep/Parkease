package com.smartparking.common.seed;

import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whether this deployment is a public demo ({@code demo} profile, {@code app.demo.enabled}), and which accounts are
 * its showcase logins. The showcase logins are the seeded {@code @parkease.dev} addresses: everyone on the internet
 * knows their password, so in demo mode they cannot be locked out (password change, suspension) and receive no mail.
 */
@Component
public class DemoMode {

    /** Domain of the seeded demo logins (admin@, owner@, driver@, owner.north@ ...). */
    public static final String SHOWCASE_DOMAIN = "@parkease.dev";

    private final boolean enabled;

    public DemoMode(@Value("${app.demo.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean isShowcaseAccount(String email) {
        return email != null && email.toLowerCase(Locale.ROOT).endsWith(SHOWCASE_DOMAIN);
    }

    /** A showcase account of a demo deployment: its credentials and status are not the visitor's to change. */
    public boolean isLockedAccount(String email) {
        return enabled && isShowcaseAccount(email);
    }
}
