package com.smartparking.listing;

import static com.smartparking.support.ListingTestSupport.basicsJson;
import static com.smartparking.support.ListingTestSupport.createListing;
import static com.smartparking.support.ListingTestSupport.puneCityId;
import static com.smartparking.support.OwnerTestSupport.unverifiedOwner;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.location.CityRepository;
import com.smartparking.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class OwnerListingControllerTest {

    @Autowired MockMvc mvc;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;

    String auth;
    Long pune;

    @BeforeEach
    void setUp() throws Exception {
        auth = unverifiedOwner(mvc, "lister@example.com");
        pune = puneCityId(cities);
    }

    @Test
    void createsDraftListingAndReadsItBack() throws Exception {
        Long id = createListing(mvc, auth, pune);
        mvc.perform(get("/api/v1/owner/listings/" + id).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.cityName").value("Pune"))
                .andExpect(jsonPath("$.stateName").value("Maharashtra"))
                .andExpect(jsonPath("$.photos.length()").value(0))
                .andExpect(jsonPath("$.autoApprove").value(true))
                .andExpect(jsonPath("$.cancellationPolicy").value("MODERATE"));
        mvc.perform(get("/api/v1/owner/listings").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.content[0].slotCount").value(0));
    }

    @Test
    void rejectsPinFarFromCity() throws Exception {
        String json = basicsJson(pune, "Far").replace("18.5204", "28.6139").replace("73.8567", "77.2090");
        mvc.perform(post("/api/v1/owner/listings").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOCATION_OUTSIDE_CITY"))
                .andExpect(jsonPath("$.detail").value(containsString("Pune")));
    }

    @Test
    void validatesBasics() throws Exception {
        mvc.perform(post("/api/v1/owner/listings").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cityId":%d,"title":"","address":"x","pincode":"012345","lat":18.5,"lng":73.8,"listingType":"OFFICE"}"""
                                .formatted(pune)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void otherOwnersGet404() throws Exception {
        Long id = createListing(mvc, auth, pune);
        String other = unverifiedOwner(mvc, "other@example.com");
        mvc.perform(get("/api/v1/owner/listings/" + id).header(HttpHeaders.AUTHORIZATION, other))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/owner/listings/" + id).header(HttpHeaders.AUTHORIZATION, other))
                .andExpect(status().isNotFound());
    }

    @Test
    void savesPricingAndAmenities() throws Exception {
        Long id = createListing(mvc, auth, pune);
        mvc.perform(put("/api/v1/owner/listings/" + id + "/pricing").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pricePerHour":30,"pricePerDay":200,"pricePerMonth":3500,"cancellationPolicy":"FLEXIBLE",
                                 "autoApprove":false,"amenities":["WELL_LIT","CCTV"],"rules":"No overnight parking"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pricePerHour").value(30.0))
                .andExpect(jsonPath("$.pricePerDay").value(200.0))
                .andExpect(jsonPath("$.autoApprove").value(false))
                .andExpect(jsonPath("$.amenities[0]").value("CCTV"))
                .andExpect(jsonPath("$.amenities[1]").value("WELL_LIT"));
    }

    @Test
    void rejectsInconsistentPricing() throws Exception {
        Long id = createListing(mvc, auth, pune);
        mvc.perform(put("/api/v1/owner/listings/" + id + "/pricing").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pricePerHour":50,"pricePerDay":40,"cancellationPolicy":"MODERATE","autoApprove":true,"amenities":[]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PRICING"));
    }

    @Test
    void deleteAllowedForDraftButNotApproved() throws Exception {
        Long draft = createListing(mvc, auth, pune);
        mvc.perform(delete("/api/v1/owner/listings/" + draft).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNoContent());

        Long approved = createListing(mvc, auth, pune);
        listings.findById(approved).orElseThrow().setStatus(ListingStatus.APPROVED);
        mvc.perform(delete("/api/v1/owner/listings/" + approved).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
    }

    @Test
    void suspendedListingsCannotBeEdited() throws Exception {
        Long id = createListing(mvc, auth, pune);
        listings.findById(id).orElseThrow().setStatus(ListingStatus.SUSPENDED);
        mvc.perform(put("/api/v1/owner/listings/" + id).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(basicsJson(pune, "New title")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LISTING_SUSPENDED"));
    }
}
