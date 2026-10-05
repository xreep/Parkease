package com.smartparking.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.smartparking.location.City;
import com.smartparking.location.CityRepository;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

public final class ListingTestSupport {

    private ListingTestSupport() {
    }

    public static Long puneCityId(CityRepository cities) {
        return cities.searchByPrefix("Pune", Limit.of(5)).stream()
                .filter(c -> c.getName().equals("Pune"))
                .map(City::getId)
                .findFirst()
                .orElseThrow();
    }

    /** Basics for a listing in central Pune. */
    public static String basicsJson(Long cityId, String title) {
        return """
                {"cityId":%d,"title":"%s","description":"Covered spot near the office","address":"FC Road, Shivajinagar",
                 "pincode":"411001","lat":18.5204,"lng":73.8567,"listingType":"OFFICE"}"""
                .formatted(cityId, title);
    }

    /** Creates a DRAFT listing and returns its id. */
    public static Long createListing(MockMvc mvc, String auth, Long cityId) throws Exception {
        String body = mvc.perform(post("/api/v1/owner/listings").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(basicsJson(cityId, "Test Spot")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    /** Adds one photo, one slot, pricing and 24x7 hours so the listing can be submitted for review. */
    public static void makeComplete(MockMvc mvc, String auth, Long id) throws Exception {
        String base = "/api/v1/owner/listings/" + id;
        mvc.perform(multipart(base + "/photos").file(OwnerTestSupport.png("p.png"))
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(post(base + "/slots").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"A-01\",\"vehicleType\":\"FOUR_WHEELER\",\"size\":\"MEDIUM\"}"))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(put(base + "/pricing").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pricePerHour\":30,\"cancellationPolicy\":\"MODERATE\",\"autoApprove\":true,\"amenities\":[\"CCTV\"]}"))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(put(base + "/hours").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"open24x7\":true,\"rules\":[]}"))
                .andExpect(status().is2xxSuccessful());
    }
}
