package com.smartparking.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {

    /** Auth limit only, as the filter was before it learned about the public routes. */
    static RateLimitFilter authOnly(int perMinute) {
        return new RateLimitFilter(List.of(RateLimitRules.auth(perMinute)), new ClientIpResolver(false, 1));
    }

    static RateLimitFilter standard(int auth, int publicReads, int uploads, int verify, boolean trustForwardedFor) {
        return new RateLimitFilter(
                RateLimitRules.standard(auth, new SecurityProperties.RateLimit(publicReads, uploads, verify)),
                new ClientIpResolver(trustForwardedFor, 1));
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
        assertThat(blocked.getHeader("Retry-After")).isEqualTo("60");
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
        RateLimitFilter all = standard(1, 1, 1, 1, false);
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
            RateLimitFilter filter = standard(10_000, 2, 10_000, 10_000, false);

            assertThat(call(filter, "GET", uri, "1.1.1.1").getStatus()).as(uri).isEqualTo(200);
            assertThat(call(filter, "GET", uri, "1.1.1.1").getStatus()).as(uri).isEqualTo(200);
            MockHttpServletResponse blocked = call(filter, "GET", uri, "1.1.1.1");

            assertThat(blocked.getStatus()).as(uri).isEqualTo(429);
            assertThat(blocked.getHeader("Retry-After")).isEqualTo("60");
            assertThat(blocked.getContentType()).startsWith("application/problem+json");
            assertThat(blocked.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"").contains("\"status\":429");
            assertThat(call(filter, "GET", uri, "2.2.2.2").getStatus()).as(uri).isEqualTo(200);
        }
    }

    @Test
    void publicReadsShareOneBudgetPerIp() throws Exception {
        RateLimitFilter filter = standard(10_000, 3, 10_000, 10_000, false);

        call(filter, "GET", "/api/v1/search", "1.1.1.1");
        call(filter, "GET", "/api/v1/listings/1", "1.1.1.1");
        call(filter, "GET", "/api/v1/listings/2/quote", "1.1.1.1");

        assertThat(call(filter, "GET", "/api/v1/listings/3/availability", "1.1.1.1").getStatus()).isEqualTo(429);
    }

    @Test
    void doesNotLimitOtherReadsOrNonGetMethods() throws Exception {
        RateLimitFilter filter = standard(10_000, 1, 1, 1, false);

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
        RateLimitFilter filter = standard(10_000, 10_000, 3, 10_000, false);

        assertThat(call(filter, "POST", "/api/v1/owner/listings/9/photos", "1.1.1.1").getStatus()).isEqualTo(200);
        assertThat(call(filter, "POST", "/api/v1/owner/verification", "1.1.1.1").getStatus()).isEqualTo(200);
        assertThat(call(filter, "POST", "/api/v1/bookings/4/disputes", "1.1.1.1").getStatus()).isEqualTo(200);

        MockHttpServletResponse blocked = call(filter, "POST", "/api/v1/owner/disputes/4/respond", "1.1.1.1");
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isEqualTo("60");
        assertThat(call(filter, "POST", "/api/v1/owner/listings/9/photos", "2.2.2.2").getStatus()).isEqualTo(200);
    }

    @Test
    void readingDisputesOrReorderingPhotosIsNotAnUpload() throws Exception {
        RateLimitFilter filter = standard(10_000, 10_000, 1, 10_000, false);

        for (int i = 0; i < 5; i++) {
            assertThat(call(filter, "GET", "/api/v1/disputes", "1.1.1.1").getStatus()).isEqualTo(200);
            assertThat(call(filter, "PUT", "/api/v1/owner/listings/9/photos/order", "1.1.1.1").getStatus())
                    .isEqualTo(200);
        }
    }

    @Test
    void limitsPaymentVerificationSeparately() throws Exception {
        RateLimitFilter filter = standard(10_000, 10_000, 10_000, 2, false);

        assertThat(call(filter, "POST", "/api/v1/payments/verify", "1.1.1.1").getStatus()).isEqualTo(200);
        assertThat(call(filter, "POST", "/api/v1/payments/verify", "1.1.1.1").getStatus()).isEqualTo(200);
        assertThat(call(filter, "POST", "/api/v1/payments/verify", "1.1.1.1").getStatus()).isEqualTo(429);
        // a different rule has its own budget for the same IP
        assertThat(call(filter, "GET", "/api/v1/search", "1.1.1.1").getStatus()).isEqualTo(200);
    }

    @Test
    void standardRulesMatchTheDocumentedProductionDefaults() {
        SecurityProperties.RateLimit defaults = new SecurityProperties(false, 1, false, null, 0).rateLimit();

        assertThat(defaults.publicPerMinute()).isEqualTo(120);
        assertThat(defaults.uploadPerMinute()).isEqualTo(20);
        assertThat(defaults.paymentVerifyPerMinute()).isEqualTo(30);
    }

    // --- client IP ---

    @Test
    void ignoresForwardedForUnlessTrusted() throws Exception {
        RateLimitFilter filter = standard(10_000, 1, 10_000, 10_000, false);

        for (String forwarded : List.of("5.5.5.5", "6.6.6.6")) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/search");
            request.setRemoteAddr("10.0.0.1");
            request.addHeader("X-Forwarded-For", forwarded);
            int expected = forwarded.equals("5.5.5.5") ? 200 : 429;
            assertThat(call(filter, request).getStatus()).isEqualTo(expected);
        }
    }

    @Test
    void usesTheAddressTheTrustedProxySawWhenForwardedForIsTrusted() throws Exception {
        RateLimitFilter filter = standard(10_000, 1, 10_000, 10_000, true);

        // The client may send its own X-Forwarded-For; the trusted proxy appends the real address after it.
        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/api/v1/search");
        first.setRemoteAddr("10.0.0.1");
        first.addHeader("X-Forwarded-For", "9.9.9.9, 203.0.113.7");
        assertThat(call(filter, first).getStatus()).isEqualTo(200);

        // Spoofing a different leftmost entry does not buy a fresh bucket.
        MockHttpServletRequest spoofed = new MockHttpServletRequest("GET", "/api/v1/search");
        spoofed.setRemoteAddr("10.0.0.1");
        spoofed.addHeader("X-Forwarded-For", "8.8.8.8, 203.0.113.7");
        assertThat(call(filter, spoofed).getStatus()).isEqualTo(429);

        // A different real client behind the same proxy has its own bucket.
        MockHttpServletRequest other = new MockHttpServletRequest("GET", "/api/v1/search");
        other.setRemoteAddr("10.0.0.1");
        other.addHeader("X-Forwarded-For", "198.51.100.9");
        assertThat(call(filter, other).getStatus()).isEqualTo(200);
    }

    @Test
    void clientIpResolverFallsBackToTheSocketAddress() {
        ClientIpResolver trusting = new ClientIpResolver(true, 1);
        ClientIpResolver twoHops = new ClientIpResolver(true, 2);

        MockHttpServletRequest none = new MockHttpServletRequest();
        none.setRemoteAddr("10.0.0.1");
        assertThat(trusting.resolve(none)).isEqualTo("10.0.0.1");

        MockHttpServletRequest junk = new MockHttpServletRequest();
        junk.setRemoteAddr("10.0.0.1");
        junk.addHeader("X-Forwarded-For", "not an ip!");
        assertThat(trusting.resolve(junk)).isEqualTo("10.0.0.1");

        MockHttpServletRequest blank = new MockHttpServletRequest();
        blank.setRemoteAddr("10.0.0.1");
        blank.addHeader("X-Forwarded-For", "  ");
        assertThat(trusting.resolve(blank)).isEqualTo("10.0.0.1");

        MockHttpServletRequest chain = new MockHttpServletRequest();
        chain.setRemoteAddr("10.0.0.1");
        chain.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8, 9.9.9.9");
        assertThat(trusting.resolve(chain)).isEqualTo("9.9.9.9");
        assertThat(twoHops.resolve(chain)).isEqualTo("5.6.7.8");

        MockHttpServletRequest tooShort = new MockHttpServletRequest();
        tooShort.setRemoteAddr("10.0.0.1");
        tooShort.addHeader("X-Forwarded-For", "9.9.9.9");
        assertThat(twoHops.resolve(tooShort)).isEqualTo("10.0.0.1");

        MockHttpServletRequest ipv6 = new MockHttpServletRequest();
        ipv6.setRemoteAddr("10.0.0.1");
        ipv6.addHeader("X-Forwarded-For", "2001:db8::1");
        assertThat(trusting.resolve(ipv6)).isEqualTo("2001:db8::1");

        MockHttpServletRequest direct = new MockHttpServletRequest();
        direct.setRemoteAddr("10.0.0.1");
        direct.addHeader("X-Forwarded-For", "9.9.9.9");
        assertThat(new ClientIpResolver(false, 1).resolve(direct)).isEqualTo("10.0.0.1");
    }
}
