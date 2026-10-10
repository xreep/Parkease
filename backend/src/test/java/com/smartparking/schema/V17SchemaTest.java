package com.smartparking.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class V17SchemaTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void theAdminPaymentAndRefundListsAreIndexed() {
        List<String> indexes = jdbc.queryForList("select indexname from pg_indexes where schemaname = current_schema()",
                String.class);
        assertThat(indexes).contains("idx_payments_created_at", "idx_refunds_status_created", "idx_refunds_payment");
    }
}
