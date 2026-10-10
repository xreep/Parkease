package com.smartparking.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shape of the Phase 7 migration that the code relies on. */
@IntegrationTest
class V15SchemaTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void bookingsMustBePricedWithAGstRateExplicitly() {
        // The backfill default is dropped, so an insert that forgets the rate fails instead of silently using 18%.
        String columnDefault = jdbc.queryForObject("select column_default from information_schema.columns "
                + "where table_name = 'bookings' and column_name = 'gst_percent'", String.class);
        assertThat(columnDefault).isNull();
    }

    @Test
    void creationTimeIsIndexedForTheAdminStatsRanges() {
        List<String> indexes = jdbc.queryForList("select indexname from pg_indexes where schemaname = current_schema()",
                String.class);
        assertThat(indexes).contains("idx_bookings_created_at", "idx_users_created_at");
    }

    @Test
    void refundNoticesIncludeDisputes() {
        String definition = jdbc.queryForObject("select pg_get_constraintdef(oid) from pg_constraint "
                + "where conname = 'refunds_notice_check'", String.class);
        assertThat(definition).contains("DISPUTE");
    }
}
