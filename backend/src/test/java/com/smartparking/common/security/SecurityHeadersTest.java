package com.smartparking.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class SecurityHeadersTest {

    @Autowired
    MockMvc mvc;

    @Test
    void apiResponsesCarryTheSecurityHeaders() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()"));
    }

    @Test
    void errorResponsesCarryThemToo() throws Exception {
        mvc.perform(get("/api/v1/test-secure/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void hstsIsOffByDefaultEvenOverHttps() throws Exception {
        mvc.perform(get("/api/v1/health").secure(true))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    void noCrossOriginResourcePolicyIsSentSoPublicImagesLoadFromTheFrontendHost() throws Exception {
        mvc.perform(get("/api/v1/health")).andExpect(header().doesNotExist("Cross-Origin-Resource-Policy"));
        mvc.perform(get("/uploads/public/none.jpg")).andExpect(header().doesNotExist("Cross-Origin-Resource-Policy"));
    }
}
