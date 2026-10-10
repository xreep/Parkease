package com.smartparking.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import com.smartparking.support.TestEmailConfig;

/**
 * The deployed behaviour, over a real HTTP connection (MockMvc skips the servlet container's RemoteIpValve): with
 * {@code forward-headers-strategy: native}, a connection from an internal proxy (here: loopback, which is in Tomcat's
 * default list) is attributed to the address in X-Forwarded-For.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestEmailConfig.class)
@TestPropertySource(properties = {
        "server.forward-headers-strategy=native",
        "app.security.rate-limit.public-per-minute=2"})
class ForwardedClientIpTest {

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    int search(String forwardedFor) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/search?lat=12.97&lng=77.59"));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void anInternalProxyMakesTheForwardedClientTheRateLimitKey() throws Exception {
        assertThat(search("203.0.113.50")).isEqualTo(200);
        assertThat(search("203.0.113.50")).isEqualTo(200);
        assertThat(search("203.0.113.50")).isEqualTo(429);

        // another visitor behind the same proxy has a budget of their own
        assertThat(search("203.0.113.51")).isEqualTo(200);
        // and a chain appended to by the client does not help: only the entry the proxy added counts
        assertThat(search("198.51.100.1, 203.0.113.50")).isEqualTo(429);
    }
}
