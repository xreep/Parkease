package com.smartparking.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(OutputCaptureExtension.class)
class RateLimitFilterTest {

    /** Auth limit only, as the filter was before it learned about the public routes. */
    static RateLimitFilter authOnly(int perMinute) {
        return new RateLimitFilter(List.of(RateLimitRules.auth(perMinute)));
    }

    static RateLimitFilter standard(int auth, int publicReads, int uploads, int verify) {
        return new RateLimitFilter(
                RateLimitRules.standard(auth, new SecurityProperties.RateLimit(publicReads, uploads, verify)));
    }

    /** Retry-After is the time until the bucket refills: whole seconds, at least 1 and at most one minute. */
    static long retryAfter(MockHttpServletResponse response) {
        long seconds = Long.parseLong(response.getHeader("Retry-After"));
        assertThat(seconds).isBetween(1L, 60L);
        return seconds;
    }

    MockHttpServletResponse call(RateLimitFilter filter, String uri, String ip) throws Exception {
        return call(filter, "POST", uri, ip);
    }

    MockHttpServletResponse call(RateLimitFilter filter, String method, String uri, String ip) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr(ip);
        return call(filter, request);
    }

    MockHttpServletResponse call(RateLimitFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    // --- auth limits: unchanged from the original filter ---

    @Test
    void blocksAnIpAfterTheLimitButNotOthers() throws Exception {
        RateLimitFilter filter = authOnly(3);

        for (int i = 0; i < 3; i++) {
            assertThat(call(filter, "/api/v1/auth/login", "1.1.1.1").getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse blocked = call(filter, "/api/v1/auth/login", "1.1.1.1");

        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(retryAfter(blocked)).isLessThanOrEqualTo(30);
        assertThat(blocked.getContentAsString()).contains("RATE_LIMITED");
        assertThat(blocked.getContentAsString()).contains("Too many attempts. Please wait a minute and try again.");
        assertThat(call(filter, "/api/v1/auth/login", "2.2.2.2").getStatus()).isEqualTo(200);
    }

    @Test
    void ignoresNonAuthPaths() throws Exception {
        RateLimitFilter filter = authOnly(1);

        for (int i = 0; i < 5; i++) {
            assertThat(call(filter, "/api/v1/states", "1.1.1.1").getStatus()).isEqualTo(200);
        }
    }

    @Test
    void neverBlocksCorsPreflightRequests() throws Exception {
        RateLimitFilter filter = authOnly(1);

        for (int i = 0; i < 3; i++) {
            assertThat(call(filter, "OPTIONS", "/api/v1/auth/login", "1.1.1.1").getStatus()).isEqualTo(200);
        }
        RateLimitFilter all = standard(1, 1, 1, 1);
        for (String uri : List.of("/api/v1/search", "/api/v1/listings/5", "/api/v1/payments/verify",
                "/api/v1/owner/listings/5/photos", "/api/v1/bookings/5/disputes")) {
            for (int i = 0; i < 3; i++) {
                assertThat(call(all, "OPTIONS", uri, "1.1.1.1").getStatus()).as(uri).isEqualTo(200);
            }
        }
    }

    @Test
    void neverBlocksRefreshOrLogout() throws Exception {
        RateLimitFilter filter = authOnly(1);

        for (int i = 0; i < 20; i++) {
            assertThat(call(filter, "/api/v1/auth/refresh", "1.1.1.1").getStatus()).isEqualTo(200);
            assertThat(call(filter, "/api/v1/auth/logout", "1.1.1.1").getStatus()).isEqualTo(200);
        }
        // and they do not consume the login bucket
        assertThat(call(filter, "/api/v1/auth/login", "1.1.1.1").getStatus()).isEqualTo(200);
    }

    // --- public read endpoints: 120/min in production ---

    @Test
    void limitsEachPublicReadEndpointPerIp() throws Exception {
        for (String uri : List.of("/api/v1/search", "/api/v1/listings/42", "/api/v1/listings/42/quote",
                "/api/v1/listings/42/availability", "/api/v1/listings/42/reviews")) {
            RateLimitFilter filter = standard(10_000, 2, 10_000, 10_000);

            assertThat(call(filter, "GET", uri, "1.1.1.1").getStatus()).as(uri).isEqualTo(200);
            assertThat(call(filter, "GET", uri, "1.1.1.1").getStatus()).as(uri).isEqualTo(200);
            MockHttpServletResponse blocked = call(filter, "GET", uri, "1.1.1.1");

            assertThat(blocked.getStatus()).as(uri).isEqualTo(429);
            assertThat(retryAfter(blocked)).isLessThanOrEqualTo(30);
            assertThat(blocked.getContentType()).startsWith("application/problem+json");
            assertThat(blocked.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"").contains("\"status\":429");
            assertThat(call(filter, "GET", uri, "2.2.2.2").getStatus()).as(uri).isEqualTo(200);
        }
    }

    @Test
    void publicReadsShareOneBudgetPerIp() throws Exception {
        RateLimitFilter filter = standard(10_000, 3, 10_000, 10_000);

        call(filter, "GET", "/api/v1/search", "1.1.1.1");
        call(filter, "GET", "/api/v1/listings/1", "1.1.1.1");
        call(filter, "GET", "/api/v1/listings/2/quote", "1.1.1.1");

        assertThat(call(filter, "GET", "/api/v1/listings/3/availability", "1.1.1.1").getStatus()).isEqualTo(429);
    }

    @Test
    void doesNotLimitOtherReadsOrNonGetMethods() throws Exception {
        RateLimitFilter filter = standard(10_000, 1, 1, 1);

        for (int i = 0; i < 5; i++) {
            assertThat(call(filter, "GET", "/api/v1/states", "1.1.1.1").getStatus()).isEqualTo(200);
            assertThat(call(filter, "GET", "/api/v1/cities/1", "1.1.1.1").getStatus()).isEqualTo(200);
            assertThat(call(filter, "GET", "/api/v1/me/bookings", "1.1.1.1").getStatus()).isEqualTo(200);
            assertThat(call(filter, "GET", "/api/v1/health", "1.1.1.1").getStatus()).isEqualTo(200);
            assertThat(call(filter, "POST", "/api/v1/listings/1/quote", "1.1.1.1").getStatus()).isEqualTo(200);
        }
    }

    // --- uploads and disputes: 20/min; payment verify: 30/min ---

    @Test
    void limitsUploadsAndDisputesTogether() throws Exception {
        RateLimitFilter filter = standard(10_000, 10_000, 3, 10_000);

        assertThat(call(filter, "POST", "/api/v1/owner/listings/9/photos", "1.1.1.1").getStatus()).isEqualTo(200);
        assertThat(call(filter, "POST", "/api/v1/owner/verification", "1.1.1.1").getStatus()).isEqualTo(200);
        assertThat(call(filter, "POST", "/api/v1/bookings/4/disputes", "1.1.1.1").getStatus()).isEqualTo(200);

        MockHttpServletResponse blocked = call(filter, "POST", "/api/v1/owner/disputes/4/respond", "1.1.1.1");
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(retryAfter(blocked)).isLessThanOrEqualTo(30);
        assertThat(call(filter, "POST", "/api/v1/owner/listings/9/photos", "2.2.2.2").getStatus()).isEqualTo(200);
    }

    @Test
    void readingDisputesOrReorderingPhotosIsNotAnUpload() throws Exception {
        RateLimitFilter filter = standard(10_000, 10_000, 1, 10_000);

        for (int i = 0; i < 5; i++) {
            assertThat(call(filter, "GET", "/api/v1/disputes", "1.1.1.1").getStatus()).isEqualTo(200);
            assertThat(call(filter, "PUT", "/api/v1/owner/listings/9/photos/order", "1.1.1.1").getStatus())
                    .isEqualTo(200);
        }
    }

    @Test
    void limitsPaymentVerificationSeparately() throws Exception {
        RateLimitFilter filter = standard(10_000, 10_000, 10_000, 2);

        assertThat(call(filter, "POST", "/api/v1/payments/verify", "1.1.1.1").getStatus()).isEqualTo(200);
        assertThat(call(filter, "POST", "/api/v1/payments/verify", "1.1.1.1").getStatus()).isEqualTo(200);
        assertThat(call(filter, "POST", "/api/v1/payments/verify", "1.1.1.1").getStatus()).isEqualTo(429);
        // a different rule has its own budget for the same IP
        assertThat(call(filter, "GET", "/api/v1/search", "1.1.1.1").getStatus()).isEqualTo(200);
    }

    @Test
    void standardRulesMatchTheDocumentedProductionDefaults() {
        SecurityProperties.RateLimit defaults = new SecurityProperties(false, null, 0).rateLimit();

        assertThat(defaults.publicPerMinute()).isEqualTo(120);
        assertThat(defaults.uploadPerMinute()).isEqualTo(20);
        assertThat(defaults.paymentVerifyPerMinute()).isEqualTo(30);
    }

    // --- client address and Retry-After ---

    @Test
    void keysOnTheContainersRemoteAddressAndNeverReadsForwardedForItself() throws Exception {
        // Tomcat's RemoteIpValve (forward-headers-strategy: native) rewrites getRemoteAddr() for trusted proxies; a
        // header that reaches the filter is therefore untrusted input and must not choose the bucket.
        RateLimitFilter filter = standard(10_000, 1, 10_000, 10_000);

        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/api/v1/search");
        first.setRemoteAddr("203.0.113.7");
        first.addHeader("X-Forwarded-For", "9.9.9.9");
        assertThat(call(filter, first).getStatus()).isEqualTo(200);

        MockHttpServletRequest spoofed = new MockHttpServletRequest("GET", "/api/v1/search");
        spoofed.setRemoteAddr("203.0.113.7");
        spoofed.addHeader("X-Forwarded-For", "8.8.8.8");
        assertThat(call(filter, spoofed).getStatus()).isEqualTo(429);
    }

    @Test
    void retryAfterIsTheTimeUntilTheBucketRefills() throws Exception {
        RateLimitFilter filter = authOnly(1);
        call(filter, "/api/v1/auth/login", "1.1.1.1");

        // 1 request a minute: the next token is about 60 s away.
        assertThat(retryAfter(call(filter, "/api/v1/auth/login", "1.1.1.1"))).isBetween(58L, 60L);

        RateLimitFilter sixty = authOnly(60);
        for (int i = 0; i < 60; i++) {
            call(sixty, "/api/v1/auth/login", "1.1.1.1");
        }
        // 60 requests a minute: a token every second, and never less than one second to wait.
        assertThat(retryAfter(call(sixty, "/api/v1/auth/login", "1.1.1.1"))).isEqualTo(1L);
    }

    @Test
    void rejectionsAreLoggedWithTheClientAddressAndRule(CapturedOutput output) throws Exception {
        RateLimitFilter filter = authOnly(1);
        call(filter, "/api/v1/auth/login", "198.51.100.77");
        call(filter, "/api/v1/auth/login", "198.51.100.77");

        assertThat(output.getAll()).contains("Rate limited").contains("client=198.51.100.77").contains("rule=auth");
    }
}
