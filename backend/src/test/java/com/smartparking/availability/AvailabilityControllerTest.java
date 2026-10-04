package com.smartparking.availability;

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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.test.web.servlet.ResultActions;
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

    private ResultActions putHours(String json) throws Exception {
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

    // ---- blocked times ----

    private String blocksUrl(Long id) {
        return "/api/v1/owner/listings/" + id + "/blocks";
    }

    private ResultActions postBlock(Long id, String bearer, Long slotId, Instant start, Instant end) throws Exception {
        String json = "{\"slotId\":%s,\"startTime\":\"%s\",\"endTime\":\"%s\",\"reason\":\"Maintenance\"}"
                .formatted(slotId, start, end);
        return mvc.perform(post(blocksUrl(id)).header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private Instant base() {
        return Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.HOURS);
    }

    private Long addSlot(Long id, String label) throws Exception {
        String body = mvc.perform(post("/api/v1/owner/listings/" + id + "/slots")
                        .header(HttpHeaders.AUTHORIZATION, auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"%s\",\"vehicleType\":\"FOUR_WHEELER\",\"size\":\"MEDIUM\"}".formatted(label)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    @Test
    void blocksWholeListing() throws Exception {
        Instant base = base();
        postBlock(listingId, auth, null, base, base.plus(Duration.ofHours(4)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slotId").isEmpty())
                .andExpect(jsonPath("$.slotLabel").isEmpty())
                .andExpect(jsonPath("$.reason").value("Maintenance"));
        mvc.perform(get(blocksUrl(listingId)).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void blocksSingleSlot() throws Exception {
        Long slotId = addSlot(listingId, "A-01");
        Instant base = base();
        postBlock(listingId, auth, slotId, base, base.plus(Duration.ofHours(2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slotId").value(slotId))
                .andExpect(jsonPath("$.slotLabel").value("A-01"));
    }

    @Test
    void listsUpcomingBlocksByStartAndSkipsEnded() throws Exception {
        Instant base = base();
        postBlock(listingId, auth, null, base.plus(Duration.ofDays(2)), base.plus(Duration.ofDays(2)).plusSeconds(3600))
                .andExpect(status().isCreated());
        postBlock(listingId, auth, null, base, base.plus(Duration.ofHours(1))).andExpect(status().isCreated());
        mvc.perform(get(blocksUrl(listingId)).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].startTime").value(base.toString()));
    }

    @Test
    void rejectsEndBeforeStart() throws Exception {
        Instant base = base();
        postBlock(listingId, auth, null, base, base.minus(Duration.ofHours(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BLOCK"));
        postBlock(listingId, auth, null, base, base)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BLOCK"));
    }

    @Test
    void rejectsAlreadyEndedBlock() throws Exception {
        Instant now = Instant.now();
        postBlock(listingId, auth, null, now.minus(Duration.ofHours(3)), now.minus(Duration.ofHours(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BLOCK"));
    }

    @Test
    void rejectsBlockStartingMoreThanAYearAhead() throws Exception {
        Instant start = Instant.now().plus(Duration.ofDays(366));
        postBlock(listingId, auth, null, start, start.plus(Duration.ofHours(2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BLOCK"));
    }

    @Test
    void rejectsSlotOfAnotherListing() throws Exception {
        Long other = createListing(mvc, auth, puneCityId(cities));
        Long foreignSlot = addSlot(other, "A-01");
        Instant base = base();
        postBlock(listingId, auth, foreignSlot, base, base.plus(Duration.ofHours(2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BLOCK"));
    }

    @Test
    void deletesBlock() throws Exception {
        Instant base = base();
        String body = postBlock(listingId, auth, null, base, base.plus(Duration.ofHours(2)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();
        mvc.perform(delete(blocksUrl(listingId) + "/" + id).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNoContent());
        mvc.perform(get(blocksUrl(listingId)).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void otherOwnerCannotDeleteBlock() throws Exception {
        Instant base = base();
        String body = postBlock(listingId, auth, null, base, base.plus(Duration.ofHours(2)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();
        String other = unverifiedOwner(mvc, "other-blocks@example.com");
        mvc.perform(delete(blocksUrl(listingId) + "/" + id).header(HttpHeaders.AUTHORIZATION, other))
                .andExpect(status().isNotFound());
        mvc.perform(get(blocksUrl(listingId)).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.length()").value(1));
    }
}
