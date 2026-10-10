package com.smartparking.common.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.support.AdminTestSupport;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.IntegrationTest;
import com.smartparking.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Under the demo profile (app.demo.enabled) the showcase logins cannot be locked out, and the API says it is a demo. */
@IntegrationTest
@TestPropertySource(properties = "app.demo.enabled=true")
class DemoModeEndpointsTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;

    private String auth(String email) throws Exception {
        return AuthTestSupport.bearer(AuthTestSupport.accessToken(AuthTestSupport.register(mvc, email, "DRIVER")));
    }

    private ResultActions changePassword(String auth) throws Exception {
        return mvc.perform(post("/api/v1/me/password").header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + AuthTestSupport.PASSWORD + "\",\"newPassword\":\"Another9pass\"}"));
    }

    @Test
    void healthSaysItIsADemo() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.demoMode").value(true));
    }

    @Test
    void showcaseAccountsCannotChangeTheirPassword() throws Exception {
        changePassword(auth("tester@parkease.dev"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DEMO_ACCOUNT_LOCKED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("demo")));
    }

    @Test
    void visitorsOwnAccountsStillChangeTheirPassword() throws Exception {
        changePassword(auth("visitor@example.com")).andExpect(status().isOk());
    }

    @Test
    void showcaseAccountsCannotBeSuspendedButVisitorsStillCan() throws Exception {
        String admin = AdminTestSupport.adminAuth(mvc, users, encoder, "someadmin@example.com");
        auth("tester@parkease.dev");
        auth("visitor2@example.com");
        long showcase = users.findByEmail("tester@parkease.dev").orElseThrow().getId();
        long visitor = users.findByEmail("visitor2@example.com").orElseThrow().getId();

        mvc.perform(post("/api/v1/admin/users/" + showcase + "/suspend").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"testing\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_SUSPEND"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("demo")));
        mvc.perform(post("/api/v1/admin/users/" + visitor + "/suspend").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"testing\"}"))
                .andExpect(status().isOk());
        assertThat(users.findById(showcase).orElseThrow().getStatus().name()).isEqualTo("ACTIVE");
    }
}
