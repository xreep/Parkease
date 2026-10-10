package com.smartparking.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.smartparking.support.IntegrationTest;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/** No error response may reveal stack traces, exception class names, SQL or file paths. */
@IntegrationTest
@WithMockUser
class ErrorBodyLeakTest {

    private static final Set<String> PROBLEM_FIELDS = Set.of("type", "title", "status", "detail", "instance", "code");

    @Autowired
    MockMvc mvc;

    private static void assertClean(String body) {
        assertThat(body)
                .doesNotContain("secret internals")
                .doesNotContain("Exception")
                .doesNotContain("stackTrace")
                .doesNotContain("trace")
                .doesNotContain("com.smartparking")
                .doesNotContain("org.springframework")
                .doesNotContain("java.")
                .doesNotContain(".java")
                .doesNotContain("select ")
                .doesNotContain("SQL")
                .doesNotContain("relation");
    }

    @Test
    void aForced500HasOnlyTheProblemFieldsAndNoInternals() throws Exception {
        String body = mvc.perform(get("/api/v1/test-errors/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andReturn().getResponse().getContentAsString();

        assertClean(body);
        java.util.Map<String, Object> json = JsonPath.read(body, "$");
        assertThat(json.keySet()).isSubsetOf(PROBLEM_FIELDS);
    }

    @Test
    void aDatabaseErrorDoesNotLeakTheStatementOrTheMessage() throws Exception {
        String body = mvc.perform(get("/api/v1/test-errors/sql"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn().getResponse().getContentAsString();

        assertClean(body);
    }

    @Test
    void malformedJsonAndUnknownPathsAreGenericToo() throws Exception {
        assertClean(mvc.perform(post("/api/v1/test-errors/validation")
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andReturn().getResponse().getContentAsString());

        assertClean(mvc.perform(get("/api/v1/no-such-endpoint"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void anOversizedJsonBodyIsRefusedWith413BeforeItIsParsed() throws Exception {
        String huge = "{\"name\":\"" + "a".repeat(1_048_576) + "\"}";

        mvc.perform(post("/api/v1/test-errors/validation").contentType(MediaType.APPLICATION_JSON).content(huge))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void aJsonBodyJustUnderTheLimitIsAccepted() throws Exception {
        String big = "{\"name\":\"" + "a".repeat(1_048_000) + "\"}";

        mvc.perform(post("/api/v1/test-errors/validation").contentType(MediaType.APPLICATION_JSON).content(big))
                .andExpect(status().isOk());
    }
}
