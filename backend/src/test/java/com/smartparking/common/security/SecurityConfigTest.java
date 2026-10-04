package com.smartparking.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.support.IntegrationTest;
import com.smartparking.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class SecurityConfigTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtService jwt;

    String bearer(Role role) {
        return "Bearer " + jwt.createAccessToken(new AuthUser(7L, "user@example.com", role));
    }

    @Test
    void missingTokenGives401Problem() throws Exception {
        mvc.perform(get("/api/v1/test-secure/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void garbageTokenGives401() throws Exception {
        mvc.perform(get("/api/v1/test-secure/me").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenExposesPrincipal() throws Exception {
        mvc.perform(get("/api/v1/test-secure/me").header(HttpHeaders.AUTHORIZATION, bearer(Role.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.role").value("DRIVER"));
    }

    @Test
    void wrongRoleGives403Problem() throws Exception {
        mvc.perform(get("/api/v1/test-secure/admin").header(HttpHeaders.AUTHORIZATION, bearer(Role.DRIVER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void adminRoleIsAllowed() throws Exception {
        mvc.perform(get("/api/v1/test-secure/admin").header(HttpHeaders.AUTHORIZATION, bearer(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void ownerAreaRejectsDriversAtFilterLevel() throws Exception {
        mvc.perform(get("/api/v1/owner/anything").header(HttpHeaders.AUTHORIZATION, bearer(Role.DRIVER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void openApiDocsArePublic() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void corsPreflightAllowsFrontendOrigin() throws Exception {
        mvc.perform(options("/api/v1/health")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
    }
}
