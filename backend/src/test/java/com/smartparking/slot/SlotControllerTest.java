package com.smartparking.slot;

import static com.smartparking.support.ListingTestSupport.createListing;
import static com.smartparking.support.ListingTestSupport.puneCityId;
import static com.smartparking.support.OwnerTestSupport.unverifiedOwner;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.smartparking.location.CityRepository;
import com.smartparking.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class SlotControllerTest {

    @Autowired MockMvc mvc;
    @Autowired CityRepository cities;

    String auth;
    Long listingId;

    @BeforeEach
    void setUp() throws Exception {
        auth = unverifiedOwner(mvc, "slots@example.com");
        listingId = createListing(mvc, auth, puneCityId(cities));
    }

    private String url(Long id) {
        return "/api/v1/owner/listings/" + id + "/slots";
    }

    private String addSlot(Long id, String label, String vehicle, String size) throws Exception {
        return mvc.perform(post(url(id)).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"%s\",\"vehicleType\":\"%s\",\"size\":\"%s\"}".formatted(label, vehicle, size)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private String bulkJson(String prefix, int start, int count) {
        return "{\"prefix\":\"%s\",\"startNumber\":%d,\"count\":%d,\"vehicleType\":\"FOUR_WHEELER\",\"size\":\"LARGE\"}"
                .formatted(prefix, start, count);
    }

    @Test
    void addsSlotAndListsByLabel() throws Exception {
        mvc.perform(post(url(listingId)).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"B-02\",\"vehicleType\":\"TWO_WHEELER\",\"size\":\"SMALL\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true));
        addSlot(listingId, "A-01", "FOUR_WHEELER", "MEDIUM");
        mvc.perform(get(url(listingId)).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].label").value("A-01"))
                .andExpect(jsonPath("$[1].label").value("B-02"));
    }

    @Test
    void rejectsDuplicateLabelCaseInsensitive() throws Exception {
        addSlot(listingId, "A-01", "FOUR_WHEELER", "MEDIUM");
        mvc.perform(post(url(listingId)).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"a-01\",\"vehicleType\":\"FOUR_WHEELER\",\"size\":\"MEDIUM\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_LABEL_TAKEN"));
    }

    @Test
    void rejectsInvalidLabel() throws Exception {
        mvc.perform(post(url(listingId)).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"-bad!\",\"vehicleType\":\"FOUR_WHEELER\",\"size\":\"MEDIUM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void bulkCreatesPaddedLabels() throws Exception {
        mvc.perform(post(url(listingId) + "/bulk").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(bulkJson("C-", 1, 3)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].label").value("C-01"))
                .andExpect(jsonPath("$[2].label").value("C-03"));
    }

    @Test
    void bulkUsesThreeDigitsWhenRangeReachesOneHundred() throws Exception {
        mvc.perform(post(url(listingId) + "/bulk").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(bulkJson("D", 99, 2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].label").value("D099"))
                .andExpect(jsonPath("$[1].label").value("D100"));
    }

    @Test
    void bulkIsAllOrNothing() throws Exception {
        addSlot(listingId, "C-02", "FOUR_WHEELER", "LARGE");
        mvc.perform(post(url(listingId) + "/bulk").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(bulkJson("C-", 1, 3)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_LABEL_TAKEN"));
        mvc.perform(get(url(listingId)).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void updatesAndDeletesSlot() throws Exception {
        long slotId = ((Number) JsonPath.read(addSlot(listingId, "A-01", "TWO_WHEELER", "SMALL"), "$.id")).longValue();
        mvc.perform(put(url(listingId) + "/" + slotId).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"A-09\",\"vehicleType\":\"FOUR_WHEELER\",\"size\":\"LARGE\",\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("A-09"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.size").value("LARGE"));
        mvc.perform(delete(url(listingId) + "/" + slotId).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNoContent());
        mvc.perform(get(url(listingId)).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void renamingSlotToOwnLabelOrToAnotherSlotsLabel() throws Exception {
        long first = ((Number) JsonPath.read(addSlot(listingId, "A-01", "TWO_WHEELER", "SMALL"), "$.id")).longValue();
        addSlot(listingId, "A-02", "TWO_WHEELER", "SMALL");
        String sameLabel = "{\"label\":\"a-01\",\"vehicleType\":\"TWO_WHEELER\",\"size\":\"MEDIUM\"}";
        mvc.perform(put(url(listingId) + "/" + first).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(sameLabel))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value("MEDIUM"));
        String clash = "{\"label\":\"A-02\",\"vehicleType\":\"TWO_WHEELER\",\"size\":\"SMALL\"}";
        mvc.perform(put(url(listingId) + "/" + first).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(clash))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_LABEL_TAKEN"));
    }

    @Test
    void slotOfAnotherListingIs404() throws Exception {
        Long other = createListing(mvc, auth, puneCityId(cities));
        long slotId = ((Number) JsonPath.read(addSlot(listingId, "A-01", "TWO_WHEELER", "SMALL"), "$.id")).longValue();
        mvc.perform(put(url(other) + "/" + slotId).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Z-1\",\"vehicleType\":\"TWO_WHEELER\",\"size\":\"SMALL\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete(url(other) + "/" + slotId).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNotFound());
    }

    @Test
    void otherOwnersCannotTouchSlots() throws Exception {
        String other = unverifiedOwner(mvc, "intruder@example.com");
        mvc.perform(get(url(listingId)).header(HttpHeaders.AUTHORIZATION, other))
                .andExpect(status().isNotFound());
        mvc.perform(post(url(listingId)).header(HttpHeaders.AUTHORIZATION, other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"A-01\",\"vehicleType\":\"TWO_WHEELER\",\"size\":\"SMALL\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void enforcesSlotLimit() throws Exception {
        for (String prefix : new String[] {"A-", "B-", "C-", "D-"}) {
            mvc.perform(post(url(listingId) + "/bulk").header(HttpHeaders.AUTHORIZATION, auth)
                            .contentType(MediaType.APPLICATION_JSON).content(bulkJson(prefix, 1, 50)))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post(url(listingId)).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"E-01\",\"vehicleType\":\"TWO_WHEELER\",\"size\":\"SMALL\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_LIMIT"));
    }
}
