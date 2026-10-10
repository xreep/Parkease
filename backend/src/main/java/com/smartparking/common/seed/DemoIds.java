package com.smartparking.common.seed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Primary keys and invoice serials taken from their sequences up front, so rows can be written in plain batches. */
final class DemoIds {

    private final JdbcTemplate jdbc;

    DemoIds(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code n} fresh primary keys of the table, in ascending order. */
    List<Long> next(String table, int n) {
        if (n <= 0) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(jdbc.queryForList(
                "select nextval(pg_get_serial_sequence(?, 'id')) from generate_series(1, ?)", Long.class, table, n));
        Collections.sort(ids);
        return ids;
    }

    /** The next {@code n} invoice serials, ascending. */
    List<Long> invoiceNumbers(int n) {
        if (n <= 0) {
            return List.of();
        }
        List<Long> numbers = new ArrayList<>(jdbc.queryForList(
                "select nextval('invoice_number_seq') from generate_series(1, ?)", Long.class, n));
        Collections.sort(numbers);
        return numbers;
    }

    /** {@code ?,?,?} for a parameterised IN list of {@code n} values. */
    static String placeholders(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }
}
