package com.smartparking.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

public final class AuthTestSupport {

    public static final String PASSWORD = "secret123";

    private AuthTestSupport() {
    }

    public static String registerJson(String email, String role) {
        return """
                {"name":"Ravi Kumar","email":"%s","password":"%s","phone":"9876543210","role":"%s"}
                """.formatted(email, PASSWORD, role);
    }

    /** Registers a user and returns the AuthResponse JSON. */
    public static String register(MockMvc mvc, String email, String role) throws Exception {
        return mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email, role)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    public static String accessToken(String authResponseJson) {
        return JsonPath.read(authResponseJson, "$.accessToken");
    }

    public static String refreshToken(String authResponseJson) {
        return JsonPath.read(authResponseJson, "$.refreshToken");
    }

    public static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }
}
