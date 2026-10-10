package com.smartparking.admin.locations;

import static com.smartparking.support.AdminTestSupport.adminAuth;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartparking.support.CommittedIntegrationTest;
import com.smartparking.support.DatabaseCleaner;
import com.smartparking.user.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/** Concurrent creates of the same place: one wins, the others get 409 (the unique constraints, never a 500). */
@CommittedIntegrationTest
class AdminLocationConcurrencyTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;

    String admin;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        cleanPlaces();
        admin = adminAuth(mvc, users, encoder, "lc-admin@example.com");
    }

    @AfterEach
    void tearDown() {
        cleanPlaces();
        DatabaseCleaner.clean(jdbc);
    }

    private void cleanPlaces() {
        jdbc.update("delete from cities where slug = 'race-city'");
        jdbc.update("delete from states where slug = 'race-land'");
    }

    private List<Integer> race(String path, String json, String expectedConflictCode) throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                go.await();
                var response = mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn().getResponse();
                return response.getStatus() + ":" + response.getContentAsString();
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<String> f : results) {
            String result = f.get(30, TimeUnit.SECONDS);
            int status = Integer.parseInt(result.substring(0, 3));
            statuses.add(status);
            if (status == 409) {
                assertThat(result).contains(expectedConflictCode);
            }
        }
        pool.shutdown();
        return statuses;
    }

    @Test
    void concurrentCityCreatesGiveOneCreatedAndConflictsOtherwise() throws Exception {
        long mh = jdbc.queryForObject("select id from states where slug = 'maharashtra'", Long.class);
        List<Integer> statuses = race("/api/v1/admin/cities",
                "{\"stateId\":" + mh + ",\"name\":\"Race City\",\"lat\":19.5,\"lng\":73.5}", "SLUG_TAKEN");
        assertThat(statuses).containsOnly(201, 409).filteredOn(s -> s == 201).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from cities where slug = 'race-city'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentStateCreatesGiveOneCreatedAndConflictsOtherwise() throws Exception {
        List<Integer> statuses = race("/api/v1/admin/states",
                "{\"name\":\"Race Land\",\"code\":\"RZ\",\"type\":\"UT\",\"capitalName\":\"Raceville\"}", "_TAKEN");
        assertThat(statuses).containsOnly(201, 409).filteredOn(s -> s == 201).hasSize(1);
    }
}
