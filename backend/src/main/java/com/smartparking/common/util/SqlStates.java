package com.smartparking.common.util;

import java.sql.SQLException;

/** Reads the PostgreSQL SQLState out of an exception chain. */
public final class SqlStates {

    public static final String EXCLUSION_VIOLATION = "23P01";
    public static final String UNIQUE_VIOLATION = "23505";
    public static final String DEADLOCK_DETECTED = "40P01";

    private SqlStates() {
    }

    /** SQLState of the deepest {@link SQLException} in the cause chain that has one, or null. */
    public static String of(Throwable error) {
        String state = null;
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                state = sql.getSQLState();
            }
        }
        return state;
    }
}
