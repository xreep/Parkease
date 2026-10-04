package com.smartparking.listing;

import static com.smartparking.support.ListingTestSupport.createListing;
import static com.smartparking.support.ListingTestSupport.makeComplete;
import static com.smartparking.support.ListingTestSupport.puneCityId;
import static com.smartparking.support.OwnerTestSupport.unverifiedOwner;
import static com.smartparking.support.OwnerTestSupport.verifiedOwner;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.smartparking.location.CityRepository;
import com.smartparking.owner.OwnerProfileRepository;
import com.smartparking.support.IntegrationTest;
import com.smartparking.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class ListingSubmitTest {

    @Autowired MockMvc mvc;
    @Autowired CityRepository cities;
    @Autowired UserRepository users;
    @Autowired OwnerProfileRepository profiles;
    @Autowired ParkingListingRepository listings;

    Long pune;

    @BeforeEach
    void setUp() {
        pune = puneCityId(cities);
    }

    private ResultActions action(String auth, Long id, String name) throws Exception {
        return mvc.perform(post("/api/v1/owner/listings/" + id + "/" + name).header(HttpHeaders.AUTHORIZATION, auth));
    }

    @Test
    void unverifiedOwnerCannotSubmit() throws Exception {
        String auth = unverifiedOwner(mvc, "unverified@example.com");
        Long id = createListing(mvc, auth, pune);
        makeComplete(mvc, auth, id);
        action(auth, id, "submit")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OWNER_NOT_VERIFIED"));
    }

    @Test
    void incompleteListingListsMissingParts() throws Exception {
        String auth = verifiedOwner(mvc, users, profiles, "verified1@example.com");
        Long id = createListing(mvc, auth, pune);
        action(auth, id, "submit")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LISTING_INCOMPLETE"))
                .andExpect(jsonPath("$.missing", hasSize(4)))
                .andExpect(jsonPath("$.missing[0]").value("PHOTOS"))
                .andExpect(jsonPath("$.missing[1]").value("SLOTS"))
                .andExpect(jsonPath("$.missing[2]").value("PRICING"))
                .andExpect(jsonPath("$.missing[3]").value("AVAILABILITY"));
    }

    @Test
    void inactiveSlotsDoNotCountAsComplete() throws Exception {
        String auth = verifiedOwner(mvc, users, profiles, "verified2@example.com");
        Long id = createListing(mvc, auth, pune);
        makeComplete(mvc, auth, id);
        // deactivate the only active slot
        long slotId = listingSlotId(auth, id, "A-01");
        mvc.perform(put("/api/v1/owner/listings/" + id + "/slots/" + slotId).header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"A-01\",\"vehicleType\":\"FOUR_WHEELER\",\"size\":\"MEDIUM\",\"active\":false}"))
                .andExpect(status().isOk());
        action(auth, id, "submit")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.missing", hasSize(1)))
                .andExpect(jsonPath("$.missing[0]").value("SLOTS"));
    }

    private long listingSlotId(String auth, Long id, String label) throws Exception {
        String body = mvc.perform(get("/api/v1/owner/listings/" + id + "/slots").header(HttpHeaders.AUTHORIZATION, auth))
                .andReturn().getResponse().getContentAsString();
        java.util.List<Number> ids = JsonPath.read(body, "$[?(@.label=='" + label + "')].id");
        return ids.get(0).longValue();
    }

    @Test
    void completeListingGoesToPendingReview() throws Exception {
        String auth = verifiedOwner(mvc, users, profiles, "verified3@example.com");
        Long id = createListing(mvc, auth, pune);
        makeComplete(mvc, auth, id);
        action(auth, id, "submit")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.submittedAt", not(emptyOrNullString())))
                .andExpect(jsonPath("$.rejectionReason").isEmpty());
        action(auth, id, "submit")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
    }

    @Test
    void rejectedListingCanBeResubmitted() throws Exception {
        String auth = verifiedOwner(mvc, users, profiles, "verified4@example.com");
        Long id = createListing(mvc, auth, pune);
        makeComplete(mvc, auth, id);
        ParkingListing listing = listings.findById(id).orElseThrow();
        listing.setStatus(ListingStatus.REJECTED);
        listing.setRejectionReason("Photos are blurry");
        listings.saveAndFlush(listing);
        action(auth, id, "submit")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.rejectionReason").isEmpty());
    }

    @Test
    void invalidStatusIsCheckedBeforeOwnerVerification() throws Exception {
        String auth = unverifiedOwner(mvc, "unverified2@example.com");
        Long id = createListing(mvc, auth, pune);
        listings.findById(id).orElseThrow().setStatus(ListingStatus.PENDING_REVIEW);
        action(auth, id, "submit")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
    }

    @Test
    void pauseAndResumeOnlyFromApprovedOrPaused() throws Exception {
        String auth = unverifiedOwner(mvc, "pauser@example.com");
        Long id = createListing(mvc, auth, pune);
        action(auth, id, "pause")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        action(auth, id, "resume")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        listings.findById(id).orElseThrow().setStatus(ListingStatus.APPROVED);
        action(auth, id, "pause")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));
        action(auth, id, "resume")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        action(auth, id, "resume")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
    }

    @Test
    void otherOwnersGet404() throws Exception {
        String auth = unverifiedOwner(mvc, "owner-a@example.com");
        Long id = createListing(mvc, auth, pune);
        String other = unverifiedOwner(mvc, "owner-b@example.com");
        action(other, id, "submit").andExpect(status().isNotFound());
        action(other, id, "pause").andExpect(status().isNotFound());
        action(other, id, "resume").andExpect(status().isNotFound());
    }
}
