package com.smartparking.auth;

import static com.smartparking.support.AuthTestSupport.PASSWORD;
import static com.smartparking.support.AuthTestSupport.refreshToken;
import static com.smartparking.support.AuthTestSupport.register;
import static com.smartparking.support.AuthTestSupport.registerJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.owner.OwnerProfileRepository;
import com.smartparking.support.IntegrationTest;
import com.smartparking.user.User;
import com.smartparking.user.UserRepository;
import com.smartparking.user.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class AuthControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    OwnerProfileRepository ownerProfiles;

    ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    ResultActions login(String email, String password) throws Exception {
        return postJson("/api/v1/auth/login", """
                {"email":"%s","password":"%s"}""".formatted(email, password));
    }

    @Test
    void registersDriverAndReturnsTokens() throws Exception {
        postJson("/api/v1/auth/register", registerJson("Ravi@Example.com", "DRIVER"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.user.email").value("ravi@example.com"))
                .andExpect(jsonPath("$.user.role").value("DRIVER"))
                .andExpect(jsonPath("$.user.emailVerified").value(false));
    }

    @Test
    void registeringOwnerCreatesOwnerProfile() throws Exception {
        register(mvc, "owner1@example.com", "OWNER");

        Long id = users.findByEmail("owner1@example.com").orElseThrow().getId();
        assertThat(ownerProfiles.findById(id)).isPresent();
    }

    @Test
    void duplicateEmailIsConflict() throws Exception {
        register(mvc, "dup@example.com", "DRIVER");

        postJson("/api/v1/auth/register", registerJson("DUP@example.com", "DRIVER"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @Test
    void cannotSelfRegisterAsAdmin() throws Exception {
        postJson("/api/v1/auth/register", registerJson("boss@example.com", "ADMIN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ROLE"));
    }

    @Test
    void invalidInputReturnsFieldErrors() throws Exception {
        postJson("/api/v1/auth/register", """
                {"name":"","email":"not-an-email","password":"short","phone":"12345","role":"DRIVER"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItems("name", "email", "password", "phone")));
    }

    @Test
    void loginWithCorrectPasswordReturnsTokens() throws Exception {
        register(mvc, "login@example.com", "DRIVER");

        login("LOGIN@example.com", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("login@example.com"));
    }

    @Test
    void loginWithWrongPasswordIs401() throws Exception {
        register(mvc, "wrongpw@example.com", "DRIVER");

        login("wrongpw@example.com", "nope12345")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void loginForUnknownEmailIs401() throws Exception {
        login("ghost@example.com", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void suspendedUserCannotLogIn() throws Exception {
        register(mvc, "suspended@example.com", "DRIVER");
        User user = users.findByEmail("suspended@example.com").orElseThrow();
        user.setStatus(UserStatus.SUSPENDED);

        login("suspended@example.com", PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
    }

    @Test
    void refreshRotatesTheToken() throws Exception {
        String oldRefresh = refreshToken(register(mvc, "refresh@example.com", "DRIVER"));
        String body = """
                {"refreshToken":"%s"}""".formatted(oldRefresh);

        postJson("/api/v1/auth/refresh", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());

        postJson("/api/v1/auth/refresh", body)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void reusingARotatedRefreshTokenRevokesAllSessions() throws Exception {
        String oldRefresh = refreshToken(register(mvc, "reuse@example.com", "DRIVER"));
        String rotated = postJson("/api/v1/auth/refresh", """
                {"refreshToken":"%s"}""".formatted(oldRefresh))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String newRefresh = refreshToken(rotated);

        postJson("/api/v1/auth/refresh", """
                {"refreshToken":"%s"}""".formatted(oldRefresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        postJson("/api/v1/auth/refresh", """
                {"refreshToken":"%s"}""".formatted(newRefresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void logoutRevokesTheRefreshToken() throws Exception {
        String refresh = refreshToken(register(mvc, "logout@example.com", "DRIVER"));
        String body = """
                {"refreshToken":"%s"}""".formatted(refresh);

        postJson("/api/v1/auth/logout", body).andExpect(status().isNoContent());

        postJson("/api/v1/auth/refresh", body).andExpect(status().isUnauthorized());
    }
}
