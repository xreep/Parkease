package com.smartparking.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** The real filter chain with small limits: public search is cut off per IP with a 429 problem and Retry-After. */
@IntegrationTest
@TestPropertySource(properties = {
        "app.security.rate-limit.public-per-minute=3",
        "app.security.trust-forwarded-for=true"})
class PublicRateLimitTest {

    @Autowired
    MockMvc mvc;

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    @Test
    void searchIsRateLimitedPerIpWith429AndRetryAfter() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/v1/search?lat=12.97&lng=77.59").with(from("198.51.100.1"))).andExpect(status().isOk());
        }

        mvc.perform(get("/api/v1/search?lat=12.97&lng=77.59").with(from("198.51.100.1")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.status").value(429));

        // another client is unaffected, and so is a non-public endpoint of the throttled client
        mvc.perform(get("/api/v1/search?lat=12.97&lng=77.59").with(from("198.51.100.2"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/states").with(from("198.51.100.1"))).andExpect(status().isOk());
    }

    @Test
    void theThrottledResponseStillCarriesCorsHeaders() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/v1/listings/1/quote").with(from("198.51.100.3")));
        }

        mvc.perform(get("/api/v1/listings/1/quote").with(from("198.51.100.3"))
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
    }

    @Test
    void forwardedForIsHonouredWhenTrusted() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/v1/search?lat=12.97&lng=77.59").with(from("10.0.0.1")).header("X-Forwarded-For", "203.0.113.50"))
                    .andExpect(status().isOk());
        }

        mvc.perform(get("/api/v1/search?lat=12.97&lng=77.59").with(from("10.0.0.1")).header("X-Forwarded-For", "203.0.113.50"))
                .andExpect(status().isTooManyRequests());
        mvc.perform(get("/api/v1/search?lat=12.97&lng=77.59").with(from("10.0.0.1")).header("X-Forwarded-For", "203.0.113.51"))
                .andExpect(status().isOk());
    }

    @Test
    void preflightIsNeverThrottled() throws Exception {
        for (int i = 0; i < 6; i++) {
            mvc.perform(options("/api/v1/search").with(from("198.51.100.4"))
                            .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                    .andExpect(status().isOk());
        }
    }
}
