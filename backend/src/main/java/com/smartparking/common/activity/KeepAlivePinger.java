package com.smartparking.common.activity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Hosts on free plans (Render) put a server to sleep after about 15 minutes without requests, and waking it means a
 * full restart. Every 10 minutes the server calls its own public health URL ({@code KEEP_ALIVE_URL}); that endpoint
 * never touches the database, so the server stays awake while the database can still sleep.
 */
@Component
public class KeepAlivePinger {

    private static final Logger log = LoggerFactory.getLogger(KeepAlivePinger.class);

    private final String url;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public KeepAlivePinger(ActivityProperties properties) {
        this.url = StringUtils.hasText(properties.keepAliveUrl()) ? properties.keepAliveUrl().trim() : null;
    }

    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT10M")
    public void scheduledPing() {
        ping();
    }

    /** True when the URL answered with a 2xx; never throws. */
    public boolean ping() {
        if (url == null) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build();
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status / 100 != 2) {
                log.warn("Keep-alive ping got HTTP {}", status);
                return false;
            }
            return true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Keep-alive ping failed ({})", e.getClass().getSimpleName());
            return false;
        }
    }
}
