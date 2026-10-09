package com.smartparking.support;

import org.springframework.jdbc.core.JdbcTemplate;

/** Empties every table that tests may populate (reference data such as states and cities is left alone). */
public final class DatabaseCleaner {

    private DatabaseCleaner() {
    }

    /** Truncates test data; refuses to run against any database whose name does not end in {@code _test}. */
    public static void clean(JdbcTemplate jdbc) {
        String database = jdbc.queryForObject("select current_database()", String.class);
        if (database == null || !database.endsWith("_test")) {
            throw new IllegalStateException("Refusing to truncate tables of database '" + database + "'");
        }
        jdbc.execute("TRUNCATE TABLE webhook_events, users, parking_listings CASCADE");
    }
}
