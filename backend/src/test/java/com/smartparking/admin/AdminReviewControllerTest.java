package com.smartparking.admin;

import static com.smartparking.support.AuthTestSupport.PASSWORD;
import static com.smartparking.support.ListingTestSupport.createListing;
import static com.smartparking.support.ListingTestSupport.makeComplete;
import static com.smartparking.support.ListingTestSupport.puneCityId;
import static com.smartparking.support.OwnerTestSupport.pdf;
import static com.smartparking.support.OwnerTestSupport.unverifiedOwner;
import static com.smartparking.support.OwnerTestSupport.verifiedOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.smartparking.email.EmailMessage;
import com.smartparking.listing.ListingPhotoRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.owner.OwnerProfileRepository;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.IntegrationTest;
import com.smartparking.support.RecordingEmailSender;
import com.smartparking.support.TestUsers;
import com.smartparking.user.Role;
import com.smartparking.user.User;
import com.smartparking.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class AdminReviewControllerTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired OwnerProfileRepository profiles;
    @Autowired CityRepository cities;
    @Autowired ListingPhotoRepository photoRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired RecordingEmailSender emails;

    String admin;

    @BeforeEach
    void setUp() throws Exception {
        emails.clear();
        User user = TestUsers.newUser("admin-t@example.com", Role.ADMIN);
        user.setEmailVerified(true);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        users.saveAndFlush(user);
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin-t@example.com\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        admin = AuthTestSupport.bearer(AuthTestSupport.accessToken(body));
    }

    private ResultActions adminGet(String path) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, admin));
    }

    private ResultActions adminPost(String path) throws Exception {
        return mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, admin));
    }

    private ResultActions adminPostJson(String path, String json) throws Exception {
        return mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void listsPendingOwnersAndVerifiesOne() throws Exception {
        String owner = unverifiedOwner(mvc, "pending-owner@example.com");
        mvc.perform(multipart("/api/v1/owner/verification").file(pdf())
                        .param("documentType", "AADHAAR").header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isOk());
        Long userId = users.findByEmail("pending-owner@example.com").orElseThrow().getId();

        adminGet("/api/v1/admin/owners")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].email").value("pending-owner@example.com"))
                .andExpect(jsonPath("$.content[0].documentType").value("AADHAAR"))
                .andExpect(jsonPath("$.content[0].hasDocument").value(true))
                .andExpect(jsonPath("$.content[0].hasPayoutDetails").value(false))
                .andExpect(jsonPath("$.content[0].listingCount").value(0));
        adminGet("/api/v1/admin/owners/" + userId + "/document-url")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url", containsString("/api/v1/files/private")));

        adminPost("/api/v1/admin/owners/" + userId + "/verify")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.verifiedAt").isNotEmpty());
        assertThat(emails.lastTo("pending-owner@example.com").subject()).containsIgnoringCase("verified");

        mvc.perform(get("/api/v1/owner/profile").header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationStatus").value("VERIFIED"));
        adminPost("/api/v1/admin/owners/" + userId + "/verify")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
    }

    @Test
    void rejectsOwnerWithReason() throws Exception {
        String owner = unverifiedOwner(mvc, "reject-owner@example.com");
        mvc.perform(multipart("/api/v1/owner/verification").file(pdf())
                        .param("documentType", "PAN").header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isOk());
        Long userId = users.findByEmail("reject-owner@example.com").orElseThrow().getId();

        adminPostJson("/api/v1/admin/owners/" + userId + "/reject", "{\"reason\":\"\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        adminPostJson("/api/v1/admin/owners/" + userId + "/reject", "{\"reason\":\"Photo is blurry\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationStatus").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("Photo is blurry"));

        EmailMessage mail = emails.lastTo("reject-owner@example.com");
        assertThat(mail.textBody()).contains("Photo is blurry");
        assertThat(mail.subject()).contains("Verification needs attention");
        adminPostJson("/api/v1/admin/owners/" + userId + "/reject", "{\"reason\":\"Again\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
    }

    @Test
    void documentUrlIs404ForNonOwnersAndOwnersWithoutDocument() throws Exception {
        unverifiedOwner(mvc, "nodoc-owner@example.com");
        Long ownerId = users.findByEmail("nodoc-owner@example.com").orElseThrow().getId();
        Long adminId = users.findByEmail("admin-t@example.com").orElseThrow().getId();
        adminGet("/api/v1/admin/owners/" + ownerId + "/document-url").andExpect(status().isNotFound());
        adminGet("/api/v1/admin/owners/" + adminId + "/document-url").andExpect(status().isNotFound());
    }

    @Test
    void approvesListing() throws Exception {
        String owner = verifiedOwner(mvc, users, profiles, "listing-owner@example.com");
        Long id = createListing(mvc, owner, puneCityId(cities));
        makeComplete(mvc, owner, id);
        mvc.perform(post("/api/v1/owner/listings/" + id + "/submit").header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isOk());

        adminGet("/api/v1/admin/listings")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].ownerEmail").value("listing-owner@example.com"))
                .andExpect(jsonPath("$.content[0].submittedAt").isNotEmpty());
        adminGet("/api/v1/admin/queues")
                .andExpect(jsonPath("$.pendingListings").value(1));
        adminGet("/api/v1/admin/listings/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listing.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.owner.verificationStatus").value("VERIFIED"));

        adminPost("/api/v1/admin/listings/" + id + "/approve")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listing.status").value("APPROVED"))
                .andExpect(jsonPath("$.listing.approvedAt", not(emptyOrNullString())));
        assertThat(emails.lastTo("listing-owner@example.com").subject()).contains("live");
        adminGet("/api/v1/admin/queues").andExpect(jsonPath("$.pendingListings").value(0));
        adminPost("/api/v1/admin/listings/" + id + "/approve")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
    }

    @Test
    void approveIsBlockedWhenTheListingBecameIncomplete() throws Exception {
        String owner = verifiedOwner(mvc, users, profiles, "incomplete-owner@example.com");
        Long id = createListing(mvc, owner, puneCityId(cities));
        makeComplete(mvc, owner, id);
        mvc.perform(post("/api/v1/owner/listings/" + id + "/submit").header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isOk());
        photoRepository.deleteAll(photoRepository.findByListingIdOrderBySortOrderAsc(id));
        photoRepository.flush();

        adminPost("/api/v1/admin/listings/" + id + "/approve")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LISTING_INCOMPLETE"))
                .andExpect(jsonPath("$.missing", hasSize(1)))
                .andExpect(jsonPath("$.missing[0]").value("PHOTOS"));
    }

    @Test
    void rejectsListingAndOwnerCanResubmit() throws Exception {
        String owner = verifiedOwner(mvc, users, profiles, "resubmit-owner@example.com");
        Long id = createListing(mvc, owner, puneCityId(cities));
        makeComplete(mvc, owner, id);
        mvc.perform(post("/api/v1/owner/listings/" + id + "/submit").header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isOk());

        adminPostJson("/api/v1/admin/listings/" + id + "/reject", "{\"reason\":\"Add a clearer entrance photo\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listing.status").value("REJECTED"));
        EmailMessage mail = emails.lastTo("resubmit-owner@example.com");
        assertThat(mail.subject()).contains("needs changes");
        assertThat(mail.textBody()).contains("Add a clearer entrance photo").contains("/owner/listings/" + id + "/edit");

        mvc.perform(get("/api/v1/owner/listings/" + id).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejectionReason").value("Add a clearer entrance photo"));
        String resubmitted = mvc.perform(post("/api/v1/owner/listings/" + id + "/submit")
                        .header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(resubmitted, "$.rejectionReason")).isNull();
    }

    @Test
    void listingDetailIs404ForUnknownId() throws Exception {
        adminGet("/api/v1/admin/listings/999999999").andExpect(status().isNotFound());
    }

    @Test
    void nonAdminsAreForbidden() throws Exception {
        String owner = unverifiedOwner(mvc, "not-admin@example.com");
        mvc.perform(get("/api/v1/admin/owners").header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/queues").header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isForbidden());
    }
}
