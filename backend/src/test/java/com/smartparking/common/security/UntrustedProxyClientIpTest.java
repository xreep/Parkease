package com.smartparking.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.support.TestEmailConfig;
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

/** A peer that is not a configured internal proxy cannot choose its own address by sending X-Forwarded-For. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestEmailConfig.class)
@TestPropertySource(properties = {
        "server.forward-headers-strategy=native",
        // only 10.9.9.9 is a trusted proxy; the test connects from loopback
        "server.tomcat.remoteip.internal-proxies=10\\.9\\.9\\.9",
        "app.security.rate-limit.public-per-minute=2"})
class UntrustedProxyClientIpTest {

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    int search(String forwardedFor) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/search?lat=12.97&lng=77.59"));
        request.header("X-Forwarded-For", forwardedFor);
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void theHeaderIsIgnoredAndTheSocketAddressIsTheKey() throws Exception {
        assertThat(search("203.0.113.1")).isEqualTo(200);
        assertThat(search("203.0.113.2")).isEqualTo(200);
        assertThat(search("203.0.113.3")).isEqualTo(429);
    }
}
