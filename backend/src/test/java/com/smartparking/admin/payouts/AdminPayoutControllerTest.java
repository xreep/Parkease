package com.smartparking.admin.payouts;

import static com.smartparking.support.AdminTestSupport.adminAuth;
import static com.smartparking.support.BookingApiSupport.bookingId;
import static com.smartparking.support.BookingApiSupport.driverWithVehicle;
import static com.smartparking.support.BookingApiSupport.payOk;
import static com.smartparking.support.BookingApiSupport.reserveOk;
import static com.smartparking.support.BookingApiSupport.tomorrowAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.BookingApiSupport.Driver;
import com.smartparking.support.CommittedIntegrationTest;
import com.smartparking.support.DatabaseCleaner;
import com.smartparking.support.ListingTestSupport;
import com.smartparking.support.RecordingEmailSender;
import com.smartparking.user.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
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
import org.springframework.test.web.servlet.ResultActions;

@CommittedIntegrationTest
class AdminPayoutControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired RecordingEmailSender emails;

    String admin;
    String ownerA;
    String ownerB;
    Long listingA;
    Long listingB;
    Long ownerAId;
    Long ownerBId;
    Driver driver;
    int hour = 6;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        emails.clear();
        admin = adminAuth(mvc, users, encoder, "po-admin@example.com");
        ownerA = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "po-owner-a@example.com", "OWNER")));
        ownerB = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "po-owner-b@example.com", "OWNER")));
        Long pune = ListingTestSupport.puneCityId(cities);
        listingA = ListingTestSupport.approvedListingAt(mvc, ownerA, listings, pune, "Spot A", 18.5204, 73.8567, 30);
        listingB = ListingTestSupport.approvedListingAt(mvc, ownerB, listings, pune, "Spot B", 18.5304, 73.8567, 30);
        ownerAId = users.findByEmail("po-owner-a@example.com").orElseThrow().getId();
        ownerBId = users.findByEmail("po-owner-b@example.com").orElseThrow().getId();
        driver = driverWithVehicle(mvc, "po-driver@example.com");
    }

    @AfterEach
    void tearDown() {
        DatabaseCleaner.clean(jdbc);
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    /** A paid booking on the listing whose owner earning (net 60.00) is made PENDING_PAYOUT; returns the earning id. */
    private long pendingEarning(Long listing) throws Exception {
        Instant start = tomorrowAt(hour);
        hour += 3;
        long id = bookingId(reserveOk(mvc, driver.auth(), listing, driver.vehicleId(), start, start.plusSeconds(7200)));
        payOk(mvc, driver.auth(), id);
        jdbc.update("update owner_earnings set status = 'PENDING_PAYOUT' where booking_id = ?", id);
        return jdbc.queryForObject("select id from owner_earnings where booking_id = ?", Long.class, id);
    }

    private ResultActions adminGet(String path) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, admin));
    }

    private ResultActions markPaid(String json) throws Exception {
        return mvc.perform(post("/api/v1/admin/payouts/mark-paid").header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String markJson(Long owner, String reference, Long... ids) {
        return "{\"ownerId\":" + owner + ",\"earningIds\":" + java.util.Arrays.toString(ids) + ",\"reference\":\""
                + reference + "\"}";
    }

    private String earningStatus(long id) {
        return jdbc.queryForObject("select status from owner_earnings where id = ?", String.class, id);
    }

    // ---- listing ------------------------------------------------------------------------------------------

    @Test
    void listsOwnersWithPendingMoneyLargestFirstWithMaskedDetails() throws Exception {
        pendingEarning(listingA);
        pendingEarning(listingA);
        pendingEarning(listingB);
        jdbc.update("update owner_profiles set payout_upi = 'rahul@okhdfc' where user_id = ?", ownerAId);
        jdbc.update("update owner_profiles set payout_bank_account = '123456781234', payout_ifsc = 'HDFC0001234', "
                + "payout_account_name = 'B Owner' where user_id = ?", ownerBId);

        adminGet("/api/v1/admin/payouts").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].ownerId").value(ownerAId))
                .andExpect(jsonPath("$[0].ownerName").value("Ravi Kumar"))
                .andExpect(jsonPath("$[0].ownerEmail").value("po-owner-a@example.com"))
                .andExpect(jsonPath("$[0].pendingAmount").value(120.0))
                .andExpect(jsonPath("$[0].earningsCount").value(2))
                .andExpect(jsonPath("$[0].payoutMethod").value("UPI"))
                .andExpect(jsonPath("$[0].payoutMasked").value("ra***@okhdfc"))
                .andExpect(jsonPath("$[1].ownerId").value(ownerBId))
                .andExpect(jsonPath("$[1].pendingAmount").value(60.0))
                .andExpect(jsonPath("$[1].payoutMethod").value("BANK"))
                .andExpect(jsonPath("$[1].payoutMasked").value("XXXX1234 · HDFC0001234"));
    }

    @Test
    void ownersWithoutPayoutDetailsHaveNullMethodAndOnlyPendingEarningsCount() throws Exception {
        long pending = pendingEarning(listingA);
        long held = pendingEarning(listingA);
        jdbc.update("update owner_earnings set status = 'HELD' where id = ?", held);
        long paid = pendingEarning(listingB);
        jdbc.update("update owner_earnings set status = 'PAID' where id = ?", paid);

        adminGet("/api/v1/admin/payouts").andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].ownerId").value(ownerAId))
                .andExpect(jsonPath("$[0].pendingAmount").value(60.0))
                .andExpect(jsonPath("$[0].earningsCount").value(1))
                .andExpect(jsonPath("$[0].payoutMethod").value(nullValue()))
                .andExpect(jsonPath("$[0].payoutMasked").value(nullValue()));
        adminGet("/api/v1/admin/payouts/" + ownerAId + "/earnings").andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(pending))
                .andExpect(jsonPath("$[0].status").value("PENDING_PAYOUT"))
                .andExpect(jsonPath("$[0].net").value(60.0))
                .andExpect(jsonPath("$[0].listingTitle").value("Spot A"))
                .andExpect(jsonPath("$[0].bookingCode").exists());
        adminGet("/api/v1/admin/payouts/" + ownerBId + "/earnings").andExpect(jsonPath("$", hasSize(0)));
        adminGet("/api/v1/admin/payouts/999999/earnings").andExpect(status().isNotFound());
    }

    @Test
    void maskingHandlesShortAndHandlelessUpiIds() throws Exception {
        pendingEarning(listingA);
        jdbc.update("update owner_profiles set payout_upi = 'x@ybl' where user_id = ?", ownerAId);
        adminGet("/api/v1/admin/payouts").andExpect(jsonPath("$[0].payoutMasked").value("x***@ybl"));
        jdbc.update("update owner_profiles set payout_upi = '9876543210' where user_id = ?", ownerAId);
        adminGet("/api/v1/admin/payouts").andExpect(jsonPath("$[0].payoutMasked").value("98***"));
    }

    // ---- mark paid ----------------------------------------------------------------------------------------

    @Test
    void markingEarningsPaidRecordsTheReferenceNotifiesTheOwnerAndAudits() throws Exception {
        long e1 = pendingEarning(listingA);
        long e2 = pendingEarning(listingA);
        long other = pendingEarning(listingB);
        emails.clear();

        markPaid(markJson(ownerAId, "  UTR123456  ", e1, e2)).andExpect(status().isOk())
                .andExpect(jsonPath("$.paidCount").value(2)).andExpect(jsonPath("$.paidAmount").value(120.0));

        for (long id : new long[] {e1, e2}) {
            assertThat(earningStatus(id)).isEqualTo("PAID");
            assertThat(jdbc.queryForMap("select payout_reference, paid_at from owner_earnings where id = ?", id))
                    .containsEntry("payout_reference", "UTR123456").doesNotContainEntry("paid_at", null);
        }
        assertThat(earningStatus(other)).isEqualTo("PENDING_PAYOUT");
        assertThat(jdbc.queryForList("select type from notifications where user_id = ?", String.class, ownerAId))
                .contains("PAYOUT_SENT");
        assertThat(emails.lastTo("po-owner-a@example.com").textBody()).contains("UTR123456").contains("120");
        assertThat(emails.sentTo("po-owner-b@example.com")).isEmpty();
        assertThat(jdbc.queryForMap("select action, target_type, target_id, details from admin_actions"))
                .containsEntry("action", "PAYOUT_MARKED_PAID").containsEntry("target_type", "OWNER")
                .containsEntry("target_id", ownerAId);
        adminGet("/api/v1/admin/payouts").andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].ownerId").value(ownerBId));
    }

    @Test
    void aPartialSelectionLeavesTheRestPending() throws Exception {
        long e1 = pendingEarning(listingA);
        long e2 = pendingEarning(listingA);
        markPaid(markJson(ownerAId, "REF-1", e1)).andExpect(status().isOk()).andExpect(jsonPath("$.paidCount").value(1))
                .andExpect(jsonPath("$.paidAmount").value(60.0));
        assertThat(earningStatus(e2)).isEqualTo("PENDING_PAYOUT");
    }

    @Test
    void everyIdMustBeThePendingEarningOfThatOwnerOrNothingChanges() throws Exception {
        long mine = pendingEarning(listingA);
        long theirs = pendingEarning(listingB);
        long held = pendingEarning(listingA);
        jdbc.update("update owner_earnings set status = 'HELD' where id = ?", held);
        long paid = pendingEarning(listingA);
        jdbc.update("update owner_earnings set status = 'PAID' where id = ?", paid);

        for (Long bad : new Long[] {theirs, held, paid, 999999L}) {
            markPaid(markJson(ownerAId, "REF-1", mine, bad)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("NOTHING_TO_PAY"));
        }
        assertThat(earningStatus(mine)).isEqualTo("PENDING_PAYOUT");
        assertThat(earningStatus(theirs)).isEqualTo("PENDING_PAYOUT");
        assertThat(jdbc.queryForObject("select count(*) from admin_actions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notifications where type = 'PAYOUT_SENT'", Integer.class))
                .isZero();
    }

    @Test
    void duplicateIdsCountOnce() throws Exception {
        long e1 = pendingEarning(listingA);
        markPaid(markJson(ownerAId, "REF-1", e1, e1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.paidCount").value(1)).andExpect(jsonPath("$.paidAmount").value(60.0));
    }

    @Test
    void markPaidValidation() throws Exception {
        long e1 = pendingEarning(listingA);
        markPaid(markJson(ownerAId, "AB", e1)).andExpect(status().isBadRequest());
        markPaid(markJson(ownerAId, "x".repeat(101), e1)).andExpect(status().isBadRequest());
        markPaid(markJson(ownerAId, "   ", e1)).andExpect(status().isBadRequest());
        markPaid("{\"ownerId\":" + ownerAId + ",\"earningIds\":[],\"reference\":\"REF-1\"}")
                .andExpect(status().isBadRequest());
        markPaid("{\"ownerId\":" + ownerAId + ",\"reference\":\"REF-1\"}").andExpect(status().isBadRequest());
        markPaid("{\"earningIds\":[" + e1 + "],\"reference\":\"REF-1\"}").andExpect(status().isBadRequest());
        assertThat(earningStatus(e1)).isEqualTo("PENDING_PAYOUT");
        markPaid(markJson(ownerAId, "x".repeat(100), e1)).andExpect(status().isOk()); // 100 characters fit
        assertThat(jdbc.queryForObject("select length(payout_reference) from owner_earnings where id = ?",
                Integer.class, e1)).isEqualTo(100);
    }

    @Test
    void twoAdminsPayingTheSameEarningsAtOnceSucceedOnlyOnce() throws Exception {
        long e1 = pendingEarning(listingA);
        long e2 = pendingEarning(listingA);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String ref = "RACE-" + i;
            Callable<Integer> call = () -> {
                go.await();
                return markPaid(markJson(ownerAId, ref, e1, e2)).andReturn().getResponse().getStatus();
            };
            results.add(pool.submit(call));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("select count(*) from admin_actions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(distinct payout_reference) from owner_earnings where status = 'PAID'",
                Integer.class)).isEqualTo(1);
    }

    // ---- CSV ----------------------------------------------------------------------------------------------

    @Test
    void exportsPendingPayoutsAsCsv() throws Exception {
        pendingEarning(listingA);
        pendingEarning(listingB);
        pendingEarning(listingB);
        jdbc.update("update users set name = '=HYPERLINK(\"x\")' where id = ?", ownerAId);
        jdbc.update("update owner_profiles set payout_upi = 'rahul@okhdfc' where user_id = ?", ownerAId);

        String csv = adminGet("/api/v1/admin/payouts?format=csv").andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", org.hamcrest.Matchers.containsString("parkease-pending-payouts-")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv")))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(csv).startsWith("﻿Owner ID,Owner,Email,Pending amount,Earnings,Payout method,Payout details\r\n");
        String[] lines = csv.split("\r\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[1]).startsWith(ownerBId + ",Ravi Kumar,po-owner-b@example.com,120.00,2,,");
        assertThat(lines[2]).contains("\"'=HYPERLINK(\"\"x\"\")\"").contains(",60.00,1,UPI,ra***@okhdfc");
        assertThat(csv).doesNotContain("rahul@");
    }

    @Test
    void invalidFormatIsRejected() throws Exception {
        adminGet("/api/v1/admin/payouts?format=xml").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void payoutEndpointsAreAdminOnly() throws Exception {
        for (String auth : new String[] {driver.auth(), ownerA}) {
            mvc.perform(get("/api/v1/admin/payouts").header(HttpHeaders.AUTHORIZATION, auth)).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/payouts/" + ownerAId + "/earnings").header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/admin/payouts/mark-paid").header(HttpHeaders.AUTHORIZATION, auth)
                    .contentType(MediaType.APPLICATION_JSON).content(markJson(ownerAId, "REF-1", 1L)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/admin/payouts")).andExpect(status().isUnauthorized());
    }
}
