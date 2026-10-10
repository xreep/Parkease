package com.smartparking.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import com.smartparking.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Production switches HSTS on; Render terminates TLS, so the header must not depend on the request looking secure. */
@IntegrationTest
@TestPropertySource(properties = "app.security.hsts=true")
class SecurityHeadersHstsTest {

    @Autowired
    MockMvc mvc;

    @Test
    void hstsIsSentWhenEnabled() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"));
    }
}
