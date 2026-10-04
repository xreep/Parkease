package com.smartparking.common.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@WithMockUser
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void apiExceptionBecomesProblemDetail() throws Exception {
        mvc.perform(get("/api/v1/test-errors/api"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("SLOT_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value("Slot taken"));
    }

    @Test
    void apiExceptionExtraPropertiesAreIncluded() throws Exception {
        mvc.perform(get("/api/v1/test-errors/extra"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LISTING_INCOMPLETE"))
                .andExpect(jsonPath("$.missing[0]").value("PHOTOS"))
                .andExpect(jsonPath("$.missing[1]").value("SLOTS"));
    }

    @Test
    void oversizedUploadIsPayloadTooLarge() throws Exception {
        mvc.perform(get("/api/v1/test-errors/too-large"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
    }

    @Test
    void validationErrorsListFields() throws Exception {
        mvc.perform(post("/api/v1/test-errors/validation")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
    }

    @Test
    void malformedJsonIsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/test-errors/validation")
                        .contentType(MediaType.APPLICATION_JSON).content("{bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void wrongParameterTypeIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/test-errors/number").param("value", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void unknownRouteIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/test-errors/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void unexpectedErrorsHideInternals() throws Exception {
        mvc.perform(get("/api/v1/test-errors/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
    }
}
