package com.smartparking.common.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.settings.PlatformSettings;
import com.smartparking.support.IntegrationTest;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** On every start under the demo profile the showcase logins are put back the way a visitor expects to find them. */
@IntegrationTest
class DemoAccountResetTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformSettings settings;
    @Autowired Clock clock;

    DemoAccountReset reset;

    @BeforeEach
    void setUp() {
        reset = new DemoAccountReset(jdbc, encoder, settings, clock, "NewDemo@1234");
    }

    private long user(String email, String role, String password, String status) {
        return jdbc.queryForObject("insert into users (name, email, phone, password_hash, role, status, email_verified)"
                + " values ('Demo', ?, null, ?, ?, ?, false) returning id", Long.class, email,
                encoder.encode(password), role, status);
    }

    private void ownerProfile(long userId, String status) {
        jdbc.update("insert into owner_profiles (user_id, verification_status) values (?, ?)", userId, status);
    }

    private void token(long userId, String hash) {
        jdbc.update("insert into refresh_tokens (user_id, token_hash, expires_at) values (?, ?, now() + interval '1 day')",
                userId, hash);
    }

    private String hash(long id) {
        return jdbc.queryForObject("select password_hash from users where id = ?", String.class, id);
    }

    private int activeTokens(long id) {
        return jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked_at is null",
                Integer.class, id);
    }

    @Test
    void aRotatedPasswordIsAppliedAndOldSessionsEnd() {
        long driver = user("driver@parkease.dev", "DRIVER", "OldDemo@1234", "ACTIVE");
        token(driver, "a".repeat(64));

        reset.run();

        assertThat(encoder.matches("NewDemo@1234", hash(driver))).isTrue();
        assertThat(activeTokens(driver)).isZero();
    }

    @Test
    void anUnchangedPasswordKeepsItsHashAndSessions() {
        long driver = user("driver@parkease.dev", "DRIVER", "NewDemo@1234", "ACTIVE");
        String before = hash(driver);
        token(driver, "b".repeat(64));

        reset.run();

        assertThat(hash(driver)).isEqualTo(before);
        assertThat(activeTokens(driver)).isEqualTo(1);
    }

    @Test
    void aSuspendedShowcaseAccountIsActivatedAgainAndMarkedEmailVerified() {
        long admin = user("admin@parkease.dev", "ADMIN", "NewDemo@1234", "SUSPENDED");

        reset.run();

        assertThat(jdbc.queryForMap("select status, email_verified from users where id = ?", admin))
                .containsEntry("status", "ACTIVE").containsEntry("email_verified", true);
    }

    @Test
    void showcaseOwnersAreVerifiedAgainButThePendingOneStaysPending() {
        long owner = user("owner@parkease.dev", "OWNER", "NewDemo@1234", "ACTIVE");
        ownerProfile(owner, "REJECTED");
        long north = user("owner.north@parkease.dev", "OWNER", "NewDemo@1234", "ACTIVE");
        ownerProfile(north, "PENDING");
        long pending = user("owner.pending@parkease.dev", "OWNER", "NewDemo@1234", "ACTIVE");
        ownerProfile(pending, "PENDING");

        reset.run();

        assertThat(status(owner)).isEqualTo("VERIFIED");
        assertThat(status(north)).isEqualTo("VERIFIED");
        assertThat(status(pending)).isEqualTo("PENDING");
    }

    private String status(long owner) {
        return jdbc.queryForObject("select verification_status from owner_profiles where user_id = ?", String.class,
                owner);
    }

    @Test
    void otherAccountsAreLeftAlone() {
        long visitor = user("visitor@example.com", "DRIVER", "Visitor@1234", "SUSPENDED");
        String before = hash(visitor);

        reset.run();

        assertThat(hash(visitor)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select status from users where id = ?", String.class, visitor))
                .isEqualTo("SUSPENDED");
    }

    @Test
    void platformSettingsGoBackToTheirDefaultsButOtherRowsStay() {
        jdbc.update("update platform_settings set value = '45' where key = 'platform_fee_percent'");
        jdbc.update("update platform_settings set value = '0' where key = 'hold_minutes'");
        jdbc.update("insert into platform_settings (key, value) values ('demo_seed_marker', 'x')");

        reset.run();

        assertThat(settings.platformFeePercent()).isEqualByComparingTo("10");
        assertThat(settings.holdMinutes()).isEqualTo(10);
        assertThat(jdbc.queryForObject("select value from platform_settings where key = 'price_tier1_max'",
                String.class)).isEqualTo("150");
        assertThat(jdbc.queryForObject("select value from platform_settings where key = 'demo_seed_marker'",
                String.class)).isEqualTo("x");
        assertThat(jdbc.queryForObject("select count(*) from platform_settings where key like '%tier%'",
                Integer.class)).isEqualTo(6);
    }
}
