package com.smartparking.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestSizeLimitFilterTest {

    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter(100);

    private static MockHttpServletRequest json(int bytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setContentType("application/json");
        request.setContent(new byte[bytes]);
        return request;
    }

    @Test
    void rejectsADeclaredBodyOverTheLimitWith413() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(json(101), response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"code\":\"PAYLOAD_TOO_LARGE\"");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void letsBodiesAtTheLimitThrough() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(json(100), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void doesNotLimitMultipartUploads() throws Exception {
        MockHttpServletRequest request = json(5_000);
        request.setContentType("multipart/form-data; boundary=x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void stopsReadingAChunkedBodyThatGrowsPastTheLimit() throws Exception {
        // MockHttpServletRequest reports the content length itself, so hide it to emulate chunked transfer.
        MockHttpServletRequest chunked = new MockHttpServletRequest("POST", "/api/v1/auth/login") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        chunked.setContentType("application/json");
        chunked.setContent("x".repeat(150).getBytes(StandardCharsets.UTF_8));
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(chunked, response, (req, res) -> seen.set((HttpServletRequest) req));

        assertThat(seen.get()).isNotNull();
        assertThatThrownBy(() -> seen.get().getInputStream().readAllBytes())
                .isInstanceOf(RequestTooLargeException.class)
                .isInstanceOf(IOException.class);
    }

    @Test
    void aChunkedBodyWithinTheLimitReadsNormally() throws Exception {
        MockHttpServletRequest chunked = new MockHttpServletRequest("POST", "/api/v1/auth/login") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        chunked.setContentType("application/json");
        chunked.setContent("x".repeat(100).getBytes(StandardCharsets.UTF_8));
        AtomicReference<byte[]> body = new AtomicReference<>();

        filter.doFilter(chunked, new MockHttpServletResponse(), (req, res) ->
                body.set(((HttpServletRequest) req).getInputStream().readAllBytes()));

        assertThat(body.get()).hasSize(100);
    }

    private static MockHttpServletRequest chunked(int bytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContentType("application/json");
        request.setCharacterEncoding("UTF-8");
        request.setContent("x".repeat(bytes).getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @Test
    void theReaderIsLimitedToo() throws Exception {
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        filter.doFilter(chunked(150), new MockHttpServletResponse(), (req, res) -> seen.set((HttpServletRequest) req));

        assertThatThrownBy(() -> seen.get().getReader().read(new char[200]))
                .isInstanceOf(RequestTooLargeException.class);
    }

    @Test
    void aReaderWithinTheLimitReadsNormally() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        filter.doFilter(chunked(100), new MockHttpServletResponse(), (req, res) ->
                body.set(new String(((HttpServletRequest) req).getReader().readLine())));

        assertThat(body.get()).hasSize(100);
    }
}
