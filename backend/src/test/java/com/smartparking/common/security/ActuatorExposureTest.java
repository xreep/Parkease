package com.smartparking.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.support.IntegrationTest;
import com.smartparking.user.Role;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class ActuatorExposureTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtService jwt;

    @Test
    void healthIsPublicAndShowsNoDetails() throws Exception {
        String body = mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("db").doesNotContain("diskSpace").doesNotContain("postgres");
    }

    @Test
    void everyOtherActuatorEndpointIsUnavailable() throws Exception {
        String admin = "Bearer " + jwt.createAccessToken(new AuthUser(1L, "admin@example.com", Role.ADMIN));
        for (String endpoint : List.of("env", "beans", "metrics", "info", "mappings", "heapdump", "threaddump",
                "loggers", "configprops", "flyway", "health/db")) {
            // anonymous callers are refused, and even an administrator finds nothing there
            mvc.perform(get("/actuator/" + endpoint)).andExpect(status().is(org.hamcrest.Matchers.oneOf(401, 404)));
            mvc.perform(get("/actuator/" + endpoint).header(HttpHeaders.AUTHORIZATION, admin))
                    .andExpect(status().isNotFound());
        }
    }
}
