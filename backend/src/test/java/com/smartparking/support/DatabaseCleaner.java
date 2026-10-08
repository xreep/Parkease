package com.smartparking.support;

import org.springframework.jdbc.core.JdbcTemplate;

/** Empties every table that tests may populate (reference data such as states and cities is left alone). */
public final class DatabaseCleaner {

    private DatabaseCleaner() {
    }

    public static void clean(JdbcTemplate jdbc) {
        jdbc.execute("TRUNCATE TABLE webhook_events, users, parking_listings CASCADE");
    }
}
