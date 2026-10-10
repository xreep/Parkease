package com.smartparking.common.seed;

import com.smartparking.settings.PlatformSettings;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs on every start of a demo deployment, after the seeders: puts the showcase logins and the platform settings back
 * the way a visitor expects to find them, so a demo that visitors have poked at repairs itself on restart.
 *
 * <ul>
 *   <li>Showcase logins ({@code *@parkease.dev}): password from {@code DEMO_PASSWORD} (a rotated password applies on
 *       the next restart and ends their sessions), status ACTIVE, e-mail verified, owners VERIFIED (except
 *       {@code owner.pending@}, whose pending verification is part of the demo).</li>
 *   <li>Platform settings: the defaults of migration V15 (fee, GST, hold and approval windows, price guidelines).</li>
 * </ul>
 * It never touches other accounts, and the demo-data dates are left as they were seeded.
 */
@Slf4j
@Component
@Profile("demo")
@Order(10)
public class DemoAccountReset implements ApplicationRunner {

    private static final String PENDING_OWNER = "owner.pending@parkease.dev";

    /** The values migration V15 inserts. */
    static final Map<String, String> DEFAULT_SETTINGS = Map.ofEntries(
            Map.entry("platform_fee_percent", "10"),
            Map.entry("gst_percent", "18"),
            Map.entry("hold_minutes", "10"),
            Map.entry("approval_hours", "2"),
            Map.entry("request_min_lead_minutes", "30"),
            Map.entry("price_tier1_min", "20"),
            Map.entry("price_tier1_max", "150"),
            Map.entry("price_tier2_min", "10"),
            Map.entry("price_tier2_max", "100"),
            Map.entry("price_tier3_min", "5"),
            Map.entry("price_tier3_max", "80"));

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final PlatformSettings settings;
    private final Clock clock;
    private final String demoPassword;

    public DemoAccountReset(JdbcTemplate jdbc, PasswordEncoder encoder, PlatformSettings settings, Clock clock,
                            @Value("${app.seed.demo-password}") String demoPassword) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.settings = settings;
        this.clock = clock;
        this.demoPassword = demoPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        run();
    }

    @Transactional
    public void run() {
        int accounts = resetAccounts();
        int changed = resetSettings();
        log.info("Demo reset: {} showcase accounts checked, {} platform settings restored", accounts, changed);
    }

    private int resetAccounts() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, email, role, password_hash from users where lower(email) like ?",
                "%" + DemoMode.SHOWCASE_DOMAIN);
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            boolean passwordChanged = !encoder.matches(demoPassword, (String) row.get("password_hash"));
            if (passwordChanged) {
                jdbc.update("update users set password_hash = ?, updated_at = now() where id = ?",
                        encoder.encode(demoPassword), id);
                jdbc.update("update refresh_tokens set revoked_at = ? where user_id = ? and revoked_at is null",
                        java.sql.Timestamp.from(clock.instant()), id);
            }
            jdbc.update("update users set status = 'ACTIVE', email_verified = true, updated_at = now() "
                    + "where id = ? and (status <> 'ACTIVE' or not email_verified)", id);
            if ("OWNER".equals(row.get("role")) && !PENDING_OWNER.equalsIgnoreCase((String) row.get("email"))) {
                jdbc.update("update owner_profiles set verification_status = 'VERIFIED', rejection_reason = null, "
                        + "verified_at = coalesce(verified_at, ?), updated_at = now() "
                        + "where user_id = ? and verification_status <> 'VERIFIED'",
                        java.sql.Timestamp.from(clock.instant()), id);
            }
        }
        return rows.size();
    }

    private int resetSettings() {
        int changed = 0;
        for (Map.Entry<String, String> setting : DEFAULT_SETTINGS.entrySet()) {
            changed += jdbc.update("update platform_settings set value = ?, updated_at = now(), updated_by = null "
                    + "where key = ? and value <> ?", setting.getValue(), setting.getKey(), setting.getValue());
        }
        settings.invalidate();
        return changed;
    }
}
