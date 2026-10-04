package com.smartparking.location;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class LocationControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void listsAll36StatesAndUnionTerritoriesWithCityCounts() throws Exception {
        mvc.perform(get("/api/v1/states"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(36))
                .andExpect(jsonPath("$[?(@.type == 'UT')].code", hasItem("DL")))
                .andExpect(jsonPath("$[?(@.code == 'MH')].cityCount", hasItem(7)))
                .andExpect(jsonPath("$[*].cityCount", everyItem(org.hamcrest.Matchers.greaterThan(0))));
    }

    @Test
    void stateDetailListsCapitalFirst() throws Exception {
        mvc.perform(get("/api/v1/states/maharashtra"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Maharashtra"))
                .andExpect(jsonPath("$.capitalName").value("Mumbai"))
                .andExpect(jsonPath("$.cities[0].name").value("Mumbai"))
                .andExpect(jsonPath("$.cities[0].capital").value(true))
                .andExpect(jsonPath("$.cities[*].name", hasItem("Pune")));
    }

    @Test
    void cityBySlugsHasCoordinates() throws Exception {
        mvc.perform(get("/api/v1/states/karnataka/cities/bengaluru"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stateCode").value("KA"))
                .andExpect(jsonPath("$.lat", closeTo(12.97, 0.05)))
                .andExpect(jsonPath("$.lng", closeTo(77.59, 0.05)));
    }

    @Test
    void searchesCitiesByPrefix() throws Exception {
        mvc.perform(get("/api/v1/cities").param("q", "pun"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("Pune")));
    }

    @Test
    void blankSearchReturnsCapitals() throws Exception {
        mvc.perform(get("/api/v1/cities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].capital", everyItem(org.hamcrest.Matchers.is(true))));
    }

    @Test
    void unknownStateIs404() throws Exception {
        mvc.perform(get("/api/v1/states/atlantis"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
