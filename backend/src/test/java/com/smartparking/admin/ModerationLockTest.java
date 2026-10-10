package com.smartparking.admin;

import static com.smartparking.support.AdminTestSupport.adminAuth;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartparking.common.security.UserStatusCache;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.CommittedIntegrationTest;
import com.smartparking.support.DatabaseCleaner;
import com.smartparking.support.ListingTestSupport;
import com.smartparking.support.OwnerTestSupport;
import com.smartparking.owner.OwnerProfileRepository;
import com.smartparking.user.UserRepository;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Moderation and the owner's / user's own writes to the same row lock it first, so the later one sees the earlier
 * one's result instead of writing a stale copy back over it. The race is made deterministic by queueing both
 * requests behind a row lock held by the test.
 */
@CommittedIntegrationTest
class ModerationLockTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired UserRepository users;
    @Autowired OwnerProfileRepository profiles;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;
    @Autowired PasswordEncoder encoder;
    @Autowired UserStatusCache statusCache;

    String admin;
    String ownerAuth;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        statusCache.clear();
        admin = adminAuth(mvc, users, encoder, "ml-admin@example.com");
        ownerAuth = OwnerTestSupport.verifiedOwner(mvc, users, profiles, "ml-owner@example.com");
    }

    @AfterEach
    void tearDown() {
        DatabaseCleaner.clean(jdbc);
    }

    /** Runs {@code first}, then {@code second}, both queued behind a row lock that is released afterwards. */
    private int[] queued(String lockSql, java.util.concurrent.Callable<Integer> first,
                         java.util.concurrent.Callable<Integer> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement st = holder.createStatement()) {
                st.execute(lockSql);
            }
            Future<Integer> a = pool.submit(first);
            Thread.sleep(700);
            Future<Integer> b = pool.submit(second);
            Thread.sleep(700);
            holder.commit();
            return new int[] {a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS)};
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anOwnerPauseQueuedBehindAnAdminSuspensionCannotUndoIt() throws Exception {
        Long listing = ListingTestSupport.approvedListingAt(mvc, ownerAuth, listings,
                ListingTestSupport.puneCityId(cities), "Lock Spot", 18.5204, 73.8567, 30);

        int[] codes = queued("select id from parking_listings where id = " + listing + " for update",
                () -> mvc.perform(post("/api/v1/admin/listings/" + listing + "/suspend")
                        .header(HttpHeaders.AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Complaints\"}")).andReturn().getResponse().getStatus(),
                () -> mvc.perform(post("/api/v1/owner/listings/" + listing + "/pause")
                        .header(HttpHeaders.AUTHORIZATION, ownerAuth)).andReturn().getResponse().getStatus());

        assertThat(codes).containsExactly(200, 409);
        assertThat(jdbc.queryForObject("select status from parking_listings where id = ?", String.class, listing))
                .isEqualTo("SUSPENDED");
    }

    @Test
    void aProfileEditQueuedBehindASuspensionDoesNotReactivateTheAccount() throws Exception {
        String driver = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "ml-driver@example.com", "DRIVER")));
        Long driverId = users.findByEmail("ml-driver@example.com").orElseThrow().getId();

        int[] codes = queued("select id from users where id = " + driverId + " for update",
                () -> mvc.perform(post("/api/v1/admin/users/" + driverId + "/suspend")
                        .header(HttpHeaders.AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Abuse\"}")).andReturn().getResponse().getStatus(),
                () -> mvc.perform(patch("/api/v1/me").header(HttpHeaders.AUTHORIZATION, driver)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"New Name\"}"))
                        .andReturn().getResponse().getStatus());

        assertThat(codes).containsExactly(200, 200);
        assertThat(jdbc.queryForMap("select status, name from users where id = ?", driverId))
                .containsEntry("status", "SUSPENDED").containsEntry("name", "New Name");
    }
}
