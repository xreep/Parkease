package com.smartparking.vehicle;

import static com.smartparking.support.AuthTestSupport.accessToken;
import static com.smartparking.support.AuthTestSupport.bearer;
import static com.smartparking.support.AuthTestSupport.register;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.smartparking.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class VehicleControllerTest {

    static final String URL = "/api/v1/me/vehicles";

    @Autowired MockMvc mvc;

    String driver;

    @BeforeEach
    void setUp() throws Exception {
        driver = bearer(accessToken(register(mvc, "vehicle-driver@example.com", "DRIVER")));
    }

    private ResultActions add(String auth, String type, String plate, String makeModel, Boolean isDefault)
            throws Exception {
        String body = """
                {"type":"%s","plateNumber":"%s"%s%s}"""
                .formatted(type, plate,
                        makeModel == null ? "" : ",\"makeModel\":\"" + makeModel + "\"",
                        isDefault == null ? "" : ",\"isDefault\":" + isDefault);
        return mvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long addOk(String type, String plate, Boolean isDefault) throws Exception {
        String json = add(driver, type, plate, null, isDefault).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    @Test
    void firstVehicleBecomesDefaultAndIsNormalised() throws Exception {
        add(driver, "FOUR_WHEELER", "mh 12 ab-1234", "Honda City", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("FOUR_WHEELER"))
                .andExpect(jsonPath("$.plateNumber").value("MH12AB1234"))
                .andExpect(jsonPath("$.makeModel").value("Honda City"))
                .andExpect(jsonPath("$.isDefault").value(true));
    }

    @Test
    void secondVehicleIsNotDefaultUnlessAskedAndThenStealsDefault() throws Exception {
        long first = addOk("FOUR_WHEELER", "MH12AB1234", null);
        add(driver, "TWO_WHEELER", "MH14CD5678", null, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isDefault").value(false));
        add(driver, "TWO_WHEELER", "KA01EF9999", null, true)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isDefault").value(true));

        mvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, driver))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].plateNumber").value("KA01EF9999"))
                .andExpect(jsonPath("$[0].isDefault").value(true))
                .andExpect(jsonPath("$[1].isDefault").value(false))
                .andExpect(jsonPath("$[2].isDefault").value(false));
        mvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, driver))
                .andExpect(jsonPath("$[?(@.id==" + first + ")].isDefault").value(false));
    }

    @Test
    void duplicatePlateWithDifferentSpacingIsRejected() throws Exception {
        addOk("FOUR_WHEELER", "MH12AB1234", null);

        add(driver, "FOUR_WHEELER", "mh-12 ab 1234", null, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLATE_TAKEN"));
    }

    @Test
    void differentDriversMayRegisterTheSamePlate() throws Exception {
        addOk("FOUR_WHEELER", "MH12AB1234", null);
        String other = bearer(accessToken(register(mvc, "vehicle-driver2@example.com", "DRIVER")));

        add(other, "FOUR_WHEELER", "MH12AB1234", null, null).andExpect(status().isCreated());
    }

    @Test
    void invalidPlateIsRejected() throws Exception {
        add(driver, "FOUR_WHEELER", "ABC123", null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PLATE"));
    }

    @Test
    void bodyValidationRejectsMissingTypeAndLongMakeModel() throws Exception {
        mvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, driver).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plateNumber\":\"MH12AB1234\"}"))
                .andExpect(status().isBadRequest());
        add(driver, "FOUR_WHEELER", "MH12AB1234", "x".repeat(61), null).andExpect(status().isBadRequest());
    }

    @Test
    void ownersAndAnonymousCannotUseDriverVehicles() throws Exception {
        String owner = bearer(accessToken(register(mvc, "vehicle-owner@example.com", "OWNER")));

        mvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DRIVERS_ONLY"));
        add(owner, "FOUR_WHEELER", "MH12AB1234", null, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DRIVERS_ONLY"));
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void limitsTenVehicles() throws Exception {
        for (int i = 0; i < 10; i++) {
            addOk("TWO_WHEELER", "MH12AB%04d".formatted(1000 + i), null);
        }

        add(driver, "TWO_WHEELER", "MH12AB2000", null, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VEHICLE_LIMIT"));
    }

    @Test
    void updateChangesFieldsAndDefault() throws Exception {
        addOk("FOUR_WHEELER", "MH12AB1234", null);
        long second = addOk("TWO_WHEELER", "MH14CD5678", null);

        mvc.perform(put(URL + "/" + second).header(HttpHeaders.AUTHORIZATION, driver)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"TWO_WHEELER\",\"plateNumber\":\"mh 14 cd 5679\",\"makeModel\":\"Activa\",\"isDefault\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plateNumber").value("MH14CD5679"))
                .andExpect(jsonPath("$.makeModel").value("Activa"))
                .andExpect(jsonPath("$.isDefault").value(true));
        mvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, driver))
                .andExpect(jsonPath("$[0].id").value(second))
                .andExpect(jsonPath("$[1].isDefault").value(false));
    }

    @Test
    void updateKeepingOwnPlateIsAllowedButTakingAnotherIsNot() throws Exception {
        long first = addOk("FOUR_WHEELER", "MH12AB1234", null);
        addOk("TWO_WHEELER", "MH14CD5678", null);

        mvc.perform(put(URL + "/" + first).header(HttpHeaders.AUTHORIZATION, driver)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"FOUR_WHEELER\",\"plateNumber\":\"MH12 AB 1234\",\"makeModel\":\"City\"}"))
                .andExpect(status().isOk());
        mvc.perform(put(URL + "/" + first).header(HttpHeaders.AUTHORIZATION, driver)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"FOUR_WHEELER\",\"plateNumber\":\"MH14CD5678\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLATE_TAKEN"));
        mvc.perform(put(URL + "/" + first).header(HttpHeaders.AUTHORIZATION, driver)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"FOUR_WHEELER\",\"plateNumber\":\"bad\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PLATE"));
    }

    @Test
    void updateWithoutIsDefaultLeavesDefaultUnchanged() throws Exception {
        long first = addOk("FOUR_WHEELER", "MH12AB1234", null);

        mvc.perform(put(URL + "/" + first).header(HttpHeaders.AUTHORIZATION, driver)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"FOUR_WHEELER\",\"plateNumber\":\"MH12AB1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(true));
    }

    @Test
    void deletingDefaultPromotesOldestRemaining() throws Exception {
        long first = addOk("FOUR_WHEELER", "MH12AB1234", null);
        long second = addOk("TWO_WHEELER", "MH14CD5678", null);
        addOk("TWO_WHEELER", "KA01EF9999", null);

        mvc.perform(delete(URL + "/" + first).header(HttpHeaders.AUTHORIZATION, driver))
                .andExpect(status().isNoContent());

        mvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, driver))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(second))
                .andExpect(jsonPath("$[0].isDefault").value(true))
                .andExpect(jsonPath("$[1].isDefault").value(false));
    }

    @Test
    void deletingTheOnlyVehicleLeavesEmptyList() throws Exception {
        long only = addOk("FOUR_WHEELER", "MH12AB1234", null);

        mvc.perform(delete(URL + "/" + only).header(HttpHeaders.AUTHORIZATION, driver))
                .andExpect(status().isNoContent());
        mvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, driver))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void anotherDriversVehicleIsNotFound() throws Exception {
        long mine = addOk("FOUR_WHEELER", "MH12AB1234", null);
        String other = bearer(accessToken(register(mvc, "vehicle-driver3@example.com", "DRIVER")));

        mvc.perform(put(URL + "/" + mine).header(HttpHeaders.AUTHORIZATION, other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"FOUR_WHEELER\",\"plateNumber\":\"MH12AB1234\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete(URL + "/" + mine).header(HttpHeaders.AUTHORIZATION, other))
                .andExpect(status().isNotFound());
        mvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, other))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
