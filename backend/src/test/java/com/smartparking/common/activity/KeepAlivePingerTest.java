package com.smartparking.common.activity;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class KeepAlivePingerTest {

    @Test
    void pingsTheConfiguredUrl() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/health", ex -> {
            hits.incrementAndGet();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/health";
            KeepAlivePinger pinger = new KeepAlivePinger(
                    new ActivityProperties(Duration.ofMinutes(15), Duration.ofMinutes(60), url));
            assertThat(pinger.ping()).isTrue();
            assertThat(hits.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNothingWithoutAUrlAndSurvivesAnUnreachableOne() {
        assertThat(new KeepAlivePinger(new ActivityProperties(null, null, null)).ping()).isFalse();
        assertThat(new KeepAlivePinger(new ActivityProperties(null, null, " ")).ping()).isFalse();
        assertThat(new KeepAlivePinger(new ActivityProperties(null, null, "http://127.0.0.1:9/nothing")).ping())
                .isFalse();
    }
}
