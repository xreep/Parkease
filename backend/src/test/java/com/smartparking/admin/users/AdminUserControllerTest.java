package com.smartparking.admin.users;

import static com.smartparking.support.AdminTestSupport.adminAuth;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.common.security.UserStatusCache;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.CommittedIntegrationTest;
import com.smartparking.support.DatabaseCleaner;
import com.smartparking.support.ListingTestSupport;
import com.smartparking.support.RecordingEmailSender;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.user.UserRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@CommittedIntegrationTest
class AdminUserControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired UserStatusCache statusCache;
    @Autowired RecordingEmailSender emails;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;

    String admin;
    String driverAuth;
    String driverRefresh;
    Long driverId;
    Long adminId;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        statusCache.clear();
        emails.clear();
        admin = adminAuth(mvc, users, encoder, "um-admin@example.com");
        adminId = users.findByEmail("um-admin@example.com").orElseThrow().getId();
        String body = AuthTestSupport.register(mvc, "um-driver@example.com", "DRIVER");
        driverAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(body));
        driverRefresh = AuthTestSupport.refreshToken(body);
        driverId = users.findByEmail("um-driver@example.com").orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        DatabaseCleaner.clean(jdbc);
        statusCache.clear();
    }

    private ResultActions get(String path) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .header(HttpHeaders.AUTHORIZATION, admin));
    }

    private ResultActions search(String q) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/users")
                .param("q", q).header(HttpHeaders.AUTHORIZATION, admin));
    }

    private ResultActions suspend(Long id, String json) throws Exception {
        return mvc.perform(post("/api/v1/admin/users/" + id + "/suspend").header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions activate(Long id) throws Exception {
        return mvc.perform(post("/api/v1/admin/users/" + id + "/activate").header(HttpHeaders.AUTHORIZATION, admin));
    }

    private ResultActions meAs(String auth) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, auth));
    }

    // ---- list ---------------------------------------------------------------------------------------------

    @Test
    void listsUsersWithFiltersSearchAndCounts() throws Exception {
        String owner = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "um-owner@example.com", "OWNER")));
        ListingTestSupport.createListing(mvc, owner, ListingTestSupport.puneCityId(cities));
        ListingTestSupport.createListing(mvc, owner, ListingTestSupport.puneCityId(cities));
        jdbc.update("update users set status = 'SUSPENDED' where email = 'um-owner@example.com'");

        get("/api/v1/admin/users").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].email").value("um-owner@example.com")) // newest first
                .andExpect(jsonPath("$.content[0].role").value("OWNER"))
                .andExpect(jsonPath("$.content[0].status").value("SUSPENDED"))
                .andExpect(jsonPath("$.content[0].listingsCount").value(2))
                .andExpect(jsonPath("$.content[0].bookingsCount").value(0))
                .andExpect(jsonPath("$.content[0].emailVerified").value(false))
                .andExpect(jsonPath("$.content[0].phone").value("9876543210"))
                .andExpect(jsonPath("$.content[0].createdAt").exists())
                .andExpect(jsonPath("$.content[0].firstName").value("Ravi"))
                .andExpect(jsonPath("$.content[0].lastName").value("Kumar"));
        get("/api/v1/admin/users?role=DRIVER").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].email").value("um-driver@example.com"));
        get("/api/v1/admin/users?status=SUSPENDED").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].email").value("um-owner@example.com"));
        get("/api/v1/admin/users?q=UM-DRIVER").andExpect(jsonPath("$.totalElements").value(1));
        get("/api/v1/admin/users?q=asha").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].role").value("ADMIN"));
        search("ravi kumar").andExpect(jsonPath("$.totalElements").value(2));
        search("%").andExpect(jsonPath("$.totalElements").value(0)); // LIKE wildcards are literal
        search("_").andExpect(jsonPath("$.totalElements").value(0));
        get("/api/v1/admin/users?role=OWNER&status=ACTIVE").andExpect(jsonPath("$.totalElements").value(0));
        get("/api/v1/admin/users?size=2&page=1").andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalPages").value(2));
        get("/api/v1/admin/users?size=1000").andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void onlyAdminsCanUseTheUserEndpoints() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/users")
                .header(HttpHeaders.AUTHORIZATION, driverAuth)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/users/" + driverId + "/suspend").header(HttpHeaders.AUTHORIZATION, driverAuth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/users/" + driverId + "/activate").header(HttpHeaders.AUTHORIZATION, driverAuth))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/users"))
                .andExpect(status().isUnauthorized());
    }

    // ---- suspend / activate -------------------------------------------------------------------------------

    @Test
    void suspendRevokesSessionsNotifiesAndAudits() throws Exception {
        meAs(driverAuth).andExpect(status().isOk()); // warms the status cache with ACTIVE

        suspend(driverId, "{\"reason\":\"  Abusive behaviour  \"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(driverId))
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.email").value("um-driver@example.com"));

        assertThat(jdbc.queryForObject("select status from users where id = ?", String.class, driverId))
                .isEqualTo("SUSPENDED");
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked_at is null",
                Integer.class, driverId)).isZero();
        assertThat(jdbc.queryForObject("select type || '|' || body from notifications where user_id = ?",
                String.class, driverId)).startsWith("ACCOUNT_SUSPENDED|").contains("Abusive behaviour");
        assertThat(emails.lastTo("um-driver@example.com").subject()).containsIgnoringCase("suspended");
        assertThat(emails.lastTo("um-driver@example.com").textBody()).contains("Abusive behaviour");
        Map<String, Object> audit = jdbc.queryForMap("select * from admin_actions");
        assertThat(audit).containsEntry("action", "USER_SUSPENDED").containsEntry("target_type", "USER")
                .containsEntry("target_id", driverId).containsEntry("admin_id", adminId);
        assertThat((String) audit.get("details")).contains("Abusive behaviour");
    }

    @Test
    void suspendedUsersAreBlockedAtOnceByTheAdminActionAndCannotLogInOrRefresh() throws Exception {
        meAs(driverAuth).andExpect(status().isOk());
        suspend(driverId, "{\"reason\":\"Spam\"}").andExpect(status().isOk());

        meAs(driverAuth).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"um-driver@example.com\",\"password\":\"" + AuthTestSupport.PASSWORD + "\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + driverRefresh + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aStatusChangedBehindTheCacheTakesEffectOnceTheCacheIsCleared() throws Exception {
        meAs(driverAuth).andExpect(status().isOk());
        jdbc.update("update users set status = 'SUSPENDED' where id = ?", driverId);
        meAs(driverAuth).andExpect(status().isOk()); // cached ACTIVE, still within the window

        statusCache.clear(); // the test hook that stands in for the 60 second window passing
        meAs(driverAuth).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
    }

    @Test
    void suspendedUsersAreBlockedOnPublicRoutesToo() throws Exception {
        suspend(driverId, "{\"reason\":\"Spam\"}").andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/states")
                        .header(HttpHeaders.AUTHORIZATION, driverAuth))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
    }

    @Test
    void activateRestoresAccessImmediately() throws Exception {
        suspend(driverId, "{\"reason\":\"Spam\"}").andExpect(status().isOk());
        meAs(driverAuth).andExpect(status().isForbidden());

        activate(driverId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        meAs(driverAuth).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"um-driver@example.com\",\"password\":\"" + AuthTestSupport.PASSWORD + "\"}"))
                .andExpect(status().isOk());
        List<String> actions = jdbc.queryForList("select action from admin_actions order by id", String.class);
        assertThat(actions).containsExactly("USER_SUSPENDED", "USER_ACTIVATED");
    }

    @Test
    void suspendValidatesTheRequest() throws Exception {
        suspend(driverId, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        suspend(driverId, "{\"reason\":\"   \"}").andExpect(status().isBadRequest());
        suspend(driverId, "{\"reason\":\"" + "x".repeat(301) + "\"}").andExpect(status().isBadRequest());
        suspend(999999L, "{\"reason\":\"Spam\"}").andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select status from users where id = ?", String.class, driverId))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select count(*) from admin_actions", Integer.class)).isZero();
    }

    @Test
    void adminsAndSelfCannotBeSuspended() throws Exception {
        suspend(adminId, "{\"reason\":\"Oops\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_SUSPEND"));
        adminAuth(mvc, users, encoder, "um-admin2@example.com");
        Long other = users.findByEmail("um-admin2@example.com").orElseThrow().getId();
        suspend(other, "{\"reason\":\"Oops\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_SUSPEND"));
        assertThat(jdbc.queryForList("select status from users where role = 'ADMIN'", String.class))
                .containsOnly("ACTIVE");
    }

    @Test
    void statusTransitionsMustMakeSense() throws Exception {
        activate(driverId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        suspend(driverId, "{\"reason\":\"Spam\"}").andExpect(status().isOk());
        suspend(driverId, "{\"reason\":\"Spam\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        activate(999999L).andExpect(status().isNotFound());
    }
}
