package com.smartparking.listing;

import static com.smartparking.support.ListingTestSupport.createListing;
import static com.smartparking.support.ListingTestSupport.makeComplete;
import static com.smartparking.support.ListingTestSupport.puneCityId;
import static com.smartparking.support.OwnerTestSupport.unverifiedOwner;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.smartparking.location.CityRepository;
import com.smartparking.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** A submitted or live listing must keep at least one photo and one active slot. */
@IntegrationTest
class ListingLiveInvariantTest {

    static final String SLOT_BODY = "{\"label\":\"A-01\",\"vehicleType\":\"FOUR_WHEELER\",\"size\":\"MEDIUM\",\"active\":%s}";

    @Autowired MockMvc mvc;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;

    private record Fixture(String auth, Long id, long photoId, long slotId) {
    }

    private Fixture complete(String email, ListingStatus status) throws Exception {
        String auth = unverifiedOwner(mvc, email);
        Long id = createListing(mvc, auth, puneCityId(cities));
        makeComplete(mvc, auth, id);
        String body = mvc.perform(get("/api/v1/owner/listings/" + id).header(HttpHeaders.AUTHORIZATION, auth))
                .andReturn().getResponse().getContentAsString();
        long photoId = ((Number) JsonPath.read(body, "$.photos[0].id")).longValue();
        long slotId = ((Number) JsonPath.read(body, "$.slots[0].id")).longValue();
        ParkingListing listing = listings.findById(id).orElseThrow();
        listing.setStatus(status);
        listings.saveAndFlush(listing);
        return new Fixture(auth, id, photoId, slotId);
    }

    private ResultActions deletePhoto(Fixture f) throws Exception {
        return mvc.perform(delete("/api/v1/owner/listings/" + f.id + "/photos/" + f.photoId)
                .header(HttpHeaders.AUTHORIZATION, f.auth));
    }

    private ResultActions deleteSlot(Fixture f) throws Exception {
        return mvc.perform(delete("/api/v1/owner/listings/" + f.id + "/slots/" + f.slotId)
                .header(HttpHeaders.AUTHORIZATION, f.auth));
    }

    private ResultActions setSlotActive(Fixture f, boolean active) throws Exception {
        return mvc.perform(put("/api/v1/owner/listings/" + f.id + "/slots/" + f.slotId)
                .header(HttpHeaders.AUTHORIZATION, f.auth).contentType(MediaType.APPLICATION_JSON)
                .content(SLOT_BODY.formatted(active)));
    }

    @Test
    void lastPhotoCannotBeDeletedWhenSubmittedOrLive() throws Exception {
        int n = 0;
        for (ListingStatus status : new ListingStatus[] {
                ListingStatus.PENDING_REVIEW, ListingStatus.APPROVED, ListingStatus.PAUSED}) {
            Fixture f = complete("photo-live" + n++ + "@example.com", status);
            deletePhoto(f)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("LISTING_INCOMPLETE"))
                    .andExpect(jsonPath("$.detail")
                            .value("A submitted or live listing needs at least one photo and one active slot"))
                    .andExpect(jsonPath("$.missing", hasSize(1)))
                    .andExpect(jsonPath("$.missing[0]").value("PHOTOS"));
        }
    }

    @Test
    void lastActiveSlotCannotBeDeletedOrDeactivatedWhenSubmittedOrLive() throws Exception {
        int n = 0;
        for (ListingStatus status : new ListingStatus[] {
                ListingStatus.PENDING_REVIEW, ListingStatus.APPROVED, ListingStatus.PAUSED}) {
            Fixture f = complete("slot-live" + n++ + "@example.com", status);
            deleteSlot(f)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("LISTING_INCOMPLETE"))
                    .andExpect(jsonPath("$.missing[0]").value("SLOTS"));
            setSlotActive(f, false)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("LISTING_INCOMPLETE"))
                    .andExpect(jsonPath("$.missing[0]").value("SLOTS"));
            setSlotActive(f, true).andExpect(status().isOk());
        }
    }

    @Test
    void anotherPhotoOrActiveSlotLetsTheOthersGo() throws Exception {
        Fixture f = complete("live-spare@example.com", ListingStatus.APPROVED);
        String base = "/api/v1/owner/listings/" + f.id;
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart(base + "/photos").file(com.smartparking.support.OwnerTestSupport.png("b.png"))
                        .header(HttpHeaders.AUTHORIZATION, f.auth))
                .andExpect(status().isCreated());
        deletePhoto(f).andExpect(status().isNoContent());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(base + "/slots")
                        .header(HttpHeaders.AUTHORIZATION, f.auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"A-02\",\"vehicleType\":\"FOUR_WHEELER\",\"size\":\"MEDIUM\"}"))
                .andExpect(status().isCreated());
        setSlotActive(f, false).andExpect(status().isOk());
        // A-02 is now the last active slot; the inactive one can be deleted freely.
        deleteSlot(f).andExpect(status().isNoContent());
    }

    @Test
    void draftAndRejectedListingsAreUnaffected() throws Exception {
        Fixture draft = complete("draft-free@example.com", ListingStatus.DRAFT);
        setSlotActive(draft, false).andExpect(status().isOk());
        deleteSlot(draft).andExpect(status().isNoContent());
        deletePhoto(draft).andExpect(status().isNoContent());

        Fixture rejected = complete("rejected-free@example.com", ListingStatus.REJECTED);
        deletePhoto(rejected).andExpect(status().isNoContent());
        deleteSlot(rejected).andExpect(status().isNoContent());
    }
}
