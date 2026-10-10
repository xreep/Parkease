package com.smartparking.common.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.support.DatabaseCleaner;
import com.smartparking.support.TestEmailConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Starting the application under the demo profile seeds everything by itself, in order (accounts, listings, then the
 * activity), and a second start finds nothing left to do. The startup runners commit their data, so it is cleaned up
 * afterwards (the tests that share the database expect it empty).
 */
@SpringBootTest
@ActiveProfiles({"test", "demo"})
@TestPropertySource(properties = "app.seed.demo-password=Demo@1234")
@Import(TestEmailConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoProfileStartupTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired DemoActivitySeeder activitySeeder;

    @AfterAll
    void clean() {
        DatabaseCleaner.clean(jdbc);
        jdbc.update("delete from platform_settings where key = ?", DemoActivitySeeder.MARKER_KEY);
    }

    @Test
    void startupSeededTheWholeDemoByItself() {
        assertThat(jdbc.queryForObject("select count(*) from users where email in ('admin@parkease.dev',"
                + " 'owner@parkease.dev', 'driver@parkease.dev')", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from parking_listings where status = 'APPROVED'", Long.class))
                .isGreaterThan(90);
        assertThat(jdbc.queryForObject("select count(*) from bookings", Long.class)).isBetween(480L, 540L);
        assertThat(jdbc.queryForObject("select count(*) from platform_settings where key = ?", Long.class,
                DemoActivitySeeder.MARKER_KEY)).isEqualTo(1);
        // A second start finds the marker and does nothing.
        assertThat(activitySeeder.seed()).isFalse();
    }
}
