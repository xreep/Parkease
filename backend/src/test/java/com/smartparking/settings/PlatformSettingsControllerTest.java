package com.smartparking.settings;

import static com.smartparking.support.AdminTestSupport.adminAuth;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.IntegrationTest;
import com.smartparking.user.UserRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class PlatformSettingsControllerTest {

    static final String VALID = """
            {"platformFeePercent":12.5,"gstPercent":18,"holdMinutes":15,"approvalHours":3,"requestMinLeadMinutes":45,
             "priceGuidelines":[{"tier":1,"minHourly":25,"maxHourly":160},{"tier":2,"minHourly":12,"maxHourly":90},
                                {"tier":3,"minHourly":6,"maxHourly":70}]}""";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformSettings settings;

    String admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = adminAuth(mvc, users, encoder, "settings-admin@example.com");
    }

    private ResultActions adminPut(String auth, String json) throws Exception {
        return mvc.perform(put("/api/v1/admin/settings").header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void returnsTheDefaultsFromTheMigration() throws Exception {
        mvc.perform(get("/api/v1/admin/settings").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platformFeePercent").value(10))
                .andExpect(jsonPath("$.gstPercent").value(18))
                .andExpect(jsonPath("$.holdMinutes").value(10))
                .andExpect(jsonPath("$.approvalHours").value(2))
                .andExpect(jsonPath("$.requestMinLeadMinutes").value(30))
                .andExpect(jsonPath("$.priceGuidelines.length()").value(3))
                .andExpect(jsonPath("$.priceGuidelines[0].tier").value(1))
                .andExpect(jsonPath("$.priceGuidelines[0].minHourly").value(20))
                .andExpect(jsonPath("$.priceGuidelines[0].maxHourly").value(150))
                .andExpect(jsonPath("$.priceGuidelines[1].minHourly").value(10))
                .andExpect(jsonPath("$.priceGuidelines[1].maxHourly").value(100))
                .andExpect(jsonPath("$.priceGuidelines[2].minHourly").value(5))
                .andExpect(jsonPath("$.priceGuidelines[2].maxHourly").value(80));
    }

    @Test
    void putUpdatesValuesRefreshesTheCacheAndAuditsTheChangedKeys() throws Exception {
        // warm the cache with the old values first
        assertThat(settings.platformFeePercent()).isEqualByComparingTo("10");

        adminPut(admin, VALID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platformFeePercent").value(12.5))
                .andExpect(jsonPath("$.holdMinutes").value(15))
                .andExpect(jsonPath("$.priceGuidelines[2].maxHourly").value(70));

        assertThat(settings.platformFeePercent()).isEqualByComparingTo("12.5");
        assertThat(settings.gstPercent()).isEqualByComparingTo("18");
        assertThat(settings.holdMinutes()).isEqualTo(15);
        assertThat(settings.approvalHours()).isEqualTo(3);
        assertThat(settings.requestMinLeadMinutes()).isEqualTo(45);
        mvc.perform(get("/api/v1/admin/settings").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.platformFeePercent").value(12.5));

        String details = jdbc.queryForObject(
                "select details from admin_actions where action = 'SETTINGS_UPDATED'", String.class);
        assertThat(details).contains("platform_fee_percent", "hold_minutes", "approval_hours",
                "request_min_lead_minutes", "price_tier1_min").doesNotContain("gst_percent");
        assertThat(jdbc.queryForObject("select target_type from admin_actions where action = 'SETTINGS_UPDATED'",
                String.class)).isEqualTo("SETTINGS");
    }

    @Test
    void anUnchangedPutWritesNoAuditRow() throws Exception {
        adminPut(admin, VALID).andExpect(status().isOk());
        adminPut(admin, VALID).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from admin_actions where action = 'SETTINGS_UPDATED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void fallsBackToTheApplicationDefaultsWhenAKeyIsMissing() {
        jdbc.update("delete from platform_settings where key in ('platform_fee_percent', 'hold_minutes', 'price_tier1_min')");
        settings.invalidate();
        assertThat(settings.platformFeePercent()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(settings.holdMinutes()).isEqualTo(10);
        assertThat(settings.priceGuideline(1).minHourly()).isEqualByComparingTo("20");
    }

    @Test
    void rejectsOutOfRangeValues() throws Exception {
        String[][] bad = {
                {"platformFeePercent", "50.5"}, {"platformFeePercent", "-1"}, {"gstPercent", "28.5"},
                {"holdMinutes", "4"}, {"holdMinutes", "61"}, {"approvalHours", "0"}, {"approvalHours", "25"},
                {"requestMinLeadMinutes", "-1"}, {"requestMinLeadMinutes", "241"}};
        for (String[] b : bad) {
            adminPut(admin, VALID.replaceAll("\"" + b[0] + "\":[0-9.]+", "\"" + b[0] + "\":" + b[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_SETTING"));
        }
    }

    @Test
    void rejectsBadGuidelines() throws Exception {
        String[] bad = {
                "[{\"tier\":1,\"minHourly\":0,\"maxHourly\":10},{\"tier\":2,\"minHourly\":1,\"maxHourly\":2},{\"tier\":3,\"minHourly\":1,\"maxHourly\":2}]",
                "[{\"tier\":1,\"minHourly\":50,\"maxHourly\":10},{\"tier\":2,\"minHourly\":1,\"maxHourly\":2},{\"tier\":3,\"minHourly\":1,\"maxHourly\":2}]",
                "[{\"tier\":1,\"minHourly\":1,\"maxHourly\":100001},{\"tier\":2,\"minHourly\":1,\"maxHourly\":2},{\"tier\":3,\"minHourly\":1,\"maxHourly\":2}]",
                "[{\"tier\":1,\"minHourly\":1,\"maxHourly\":2}]",
                "[{\"tier\":1,\"minHourly\":1,\"maxHourly\":2},{\"tier\":1,\"minHourly\":1,\"maxHourly\":2},{\"tier\":3,\"minHourly\":1,\"maxHourly\":2}]",
                "[{\"tier\":4,\"minHourly\":1,\"maxHourly\":2},{\"tier\":2,\"minHourly\":1,\"maxHourly\":2},{\"tier\":3,\"minHourly\":1,\"maxHourly\":2}]"};
        for (String guidelines : bad) {
            adminPut(admin, "{\"platformFeePercent\":10,\"gstPercent\":18,\"holdMinutes\":10,\"approvalHours\":2,"
                    + "\"requestMinLeadMinutes\":30,\"priceGuidelines\":" + guidelines + "}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_SETTING"));
        }
        assertThat(jdbc.queryForObject("select count(*) from admin_actions", Integer.class)).isZero();
    }

    @Test
    void missingFieldsAreValidationErrors() throws Exception {
        adminPut(admin, "{\"platformFeePercent\":10}").andExpect(status().isBadRequest());
    }

    @Test
    void onlyAdminsCanReadOrWrite() throws Exception {
        String driver = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "settings-driver@example.com", "DRIVER")));
        String owner = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "settings-owner@example.com", "OWNER")));
        for (String auth : new String[] {driver, owner}) {
            mvc.perform(get("/api/v1/admin/settings").header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(status().isForbidden());
            adminPut(auth, VALID).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/admin/settings")).andExpect(status().isUnauthorized());
    }
}
