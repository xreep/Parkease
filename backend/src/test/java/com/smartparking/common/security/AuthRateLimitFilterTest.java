package com.smartparking.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthRateLimitFilterTest {

    MockHttpServletResponse call(AuthRateLimitFilter filter, String uri, String ip) throws Exception {
        return call(filter, "POST", uri, ip);
    }

    MockHttpServletResponse call(AuthRateLimitFilter filter, String method, String uri, String ip) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void blocksAnIpAfterTheLimitButNotOthers() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(3);

        for (int i = 0; i < 3; i++) {
            assertThat(call(filter, "/api/v1/auth/login", "1.1.1.1").getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse blocked = call(filter, "/api/v1/auth/login", "1.1.1.1");

        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isEqualTo("60");
        assertThat(blocked.getContentAsString()).contains("RATE_LIMITED");
        assertThat(call(filter, "/api/v1/auth/login", "2.2.2.2").getStatus()).isEqualTo(200);
    }

    @Test
    void ignoresNonAuthPaths() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(1);

        for (int i = 0; i < 5; i++) {
            assertThat(call(filter, "/api/v1/states", "1.1.1.1").getStatus()).isEqualTo(200);
        }
    }

    @Test
    void neverBlocksCorsPreflightRequests() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(1);

        for (int i = 0; i < 3; i++) {
            assertThat(call(filter, "OPTIONS", "/api/v1/auth/login", "1.1.1.1").getStatus()).isEqualTo(200);
        }
    }
}
