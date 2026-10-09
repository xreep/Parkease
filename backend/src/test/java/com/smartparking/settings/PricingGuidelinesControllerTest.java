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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class PricingGuidelinesControllerTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;

    private long cityId(String slug) {
        return jdbc.queryForObject("select id from cities where slug = ?", Long.class, slug);
    }

    private ResultActions guidelines(String auth, Object cityId) throws Exception {
        return mvc.perform(get("/api/v1/pricing-guidelines?cityId=" + cityId).header(HttpHeaders.AUTHORIZATION, auth));
    }

    private String owner() throws Exception {
        return AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "guide-owner@example.com", "OWNER")));
    }

    @Test
    void guidelinesFollowTheCityTier() throws Exception {
        String owner = owner();
        guidelines(owner, cityId("mumbai")).andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value(1)).andExpect(jsonPath("$.minHourly").value(20))
                .andExpect(jsonPath("$.maxHourly").value(150));
        guidelines(owner, cityId("bhopal")).andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value(2)).andExpect(jsonPath("$.minHourly").value(10))
                .andExpect(jsonPath("$.maxHourly").value(100));
        guidelines(owner, cityId("gaya")).andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value(3)).andExpect(jsonPath("$.minHourly").value(5))
                .andExpect(jsonPath("$.maxHourly").value(80));
    }

    @Test
    void adminsSeeTheGuidelinesToo() throws Exception {
        String admin = adminAuth(mvc, users, encoder, "guide-admin@example.com");
        guidelines(admin, cityId("pune")).andExpect(status().isOk()).andExpect(jsonPath("$.tier").value(1));
    }

    @Test
    void editedGuidelinesAreServedImmediately() throws Exception {
        String admin = adminAuth(mvc, users, encoder, "guide-admin2@example.com");
        mvc.perform(put("/api/v1/admin/settings").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PlatformSettingsControllerTest.VALID))
                .andExpect(status().isOk());
        guidelines(owner(), cityId("pune")).andExpect(jsonPath("$.minHourly").value(25))
                .andExpect(jsonPath("$.maxHourly").value(160));
    }

    @Test
    void unknownCityIs404AndDriversAndAnonymousAreRefused() throws Exception {
        guidelines(owner(), 999999999L).andExpect(status().isNotFound());
        String driver = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "guide-driver@example.com", "DRIVER")));
        guidelines(driver, cityId("pune")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/pricing-guidelines?cityId=" + cityId("pune"))).andExpect(status().isUnauthorized());
    }

    @Test
    void migrationSeededTheTiers() {
        assertThat(tier("mumbai")).isEqualTo(1);
        assertThat(tier("new-delhi")).isEqualTo(1);
        assertThat(tier("bengaluru")).isEqualTo(1);
        assertThat(tier("chennai")).isEqualTo(1);
        assertThat(tier("kolkata")).isEqualTo(1);
        assertThat(tier("hyderabad")).isEqualTo(1);
        assertThat(tier("pune")).isEqualTo(1);
        assertThat(tier("ahmedabad")).isEqualTo(1);
        assertThat(tier("bhopal")).isEqualTo(2);
        assertThat(tier("jaipur")).isEqualTo(2);
        assertThat(tier("gaya")).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from cities where tier = 1", Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("select count(*) from cities where active = false", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from cities where is_capital and tier = 3", Integer.class))
                .isZero();
    }

    private int tier(String slug) {
        return jdbc.queryForObject("select tier from cities where slug = ?", Integer.class, slug);
    }
}
