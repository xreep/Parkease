package com.smartparking.availability;

import static com.smartparking.support.ListingTestSupport.createListing;
import static com.smartparking.support.ListingTestSupport.puneCityId;
import static com.smartparking.support.OwnerTestSupport.unverifiedOwner;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class AvailabilityControllerTest {

    @Autowired MockMvc mvc;
    @Autowired CityRepository cities;

    String auth;
    Long listingId;

    @BeforeEach
    void setUp() throws Exception {
        auth = unverifiedOwner(mvc, "hours@example.com");
        listingId = createListing(mvc, auth, puneCityId(cities));
    }

    private String hoursUrl() {
        return "/api/v1/owner/listings/" + listingId + "/hours";
    }

    private org.springframework.test.web.servlet.ResultActions putHours(String json) throws Exception {
        return mvc.perform(put(hoursUrl()).header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void savesWeeklyHours() throws Exception {
        putHours("""
                {"open24x7":false,"rules":[{"dayOfWeek":6,"openTime":"10:00","closeTime":"16:30"},
                {"dayOfWeek":1,"openTime":"08:00","closeTime":"20:00"}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open24x7").value(false))
                .andExpect(jsonPath("$.rules.length()").value(2))
                .andExpect(jsonPath("$.rules[0].dayOfWeek").value(1))
                .andExpect(jsonPath("$.rules[1].closeTime").value("16:30"));
        mvc.perform(get(hoursUrl()).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open24x7").value(false))
                .andExpect(jsonPath("$.rules.length()").value(2))
                .andExpect(jsonPath("$.rules[0].openTime").value("08:00"))
                .andExpect(jsonPath("$.rules[1].closeTime").value("16:30"));
    }

    @Test
    void replacesPreviousRules() throws Exception {
        putHours("""
                {"open24x7":false,"rules":[{"dayOfWeek":1,"openTime":"08:00","closeTime":"20:00"},
                {"dayOfWeek":2,"openTime":"08:00","closeTime":"20:00"}]}""").andExpect(status().isOk());
        putHours("""
                {"open24x7":false,"rules":[{"dayOfWeek":1,"openTime":"09:00","closeTime":"18:00"}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules.length()").value(1))
                .andExpect(jsonPath("$.rules[0].openTime").value("09:00"));
        mvc.perform(get(hoursUrl()).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.rules.length()").value(1));
    }

    @Test
    void open24x7ClearsRules() throws Exception {
        putHours("""
                {"open24x7":false,"rules":[{"dayOfWeek":1,"openTime":"08:00","closeTime":"20:00"}]}""")
                .andExpect(status().isOk());
        putHours("""
                {"open24x7":true,"rules":[{"dayOfWeek":1,"openTime":"08:00","closeTime":"20:00"}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open24x7").value(true))
                .andExpect(jsonPath("$.rules.length()").value(0));
        mvc.perform(get(hoursUrl()).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.open24x7").value(true))
                .andExpect(jsonPath("$.rules.length()").value(0));
    }

    @Test
    void rejectsDuplicateDays() throws Exception {
        putHours("""
                {"open24x7":false,"rules":[{"dayOfWeek":2,"openTime":"08:00","closeTime":"12:00"},
                {"dayOfWeek":2,"openTime":"13:00","closeTime":"18:00"}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_DAY"));
    }

    @Test
    void rejectsCloseBeforeOpen() throws Exception {
        putHours("""
                {"open24x7":false,"rules":[{"dayOfWeek":3,"openTime":"08:00","closeTime":"07:00"}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_HOURS"));
    }

    @Test
    void rejectsInvalidDayOfWeek() throws Exception {
        putHours("""
                {"open24x7":false,"rules":[{"dayOfWeek":8,"openTime":"08:00","closeTime":"17:00"}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void otherOwnersGet404() throws Exception {
        String other = unverifiedOwner(mvc, "other-hours@example.com");
        mvc.perform(get(hoursUrl()).header(HttpHeaders.AUTHORIZATION, other)).andExpect(status().isNotFound());
        mvc.perform(put(hoursUrl()).header(HttpHeaders.AUTHORIZATION, other)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"open24x7\":true,\"rules\":[]}"))
                .andExpect(status().isNotFound());
    }
}
