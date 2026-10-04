package com.smartparking.user;

import static com.smartparking.support.AuthTestSupport.PASSWORD;
import static com.smartparking.support.AuthTestSupport.accessToken;
import static com.smartparking.support.AuthTestSupport.bearer;
import static com.smartparking.support.AuthTestSupport.refreshToken;
import static com.smartparking.support.AuthTestSupport.register;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.support.IntegrationTest;
import com.smartparking.support.RecordingEmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class MeControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    RecordingEmailSender emails;

    @Autowired
    UserRepository users;

    String auth;
    String refresh;

    @BeforeEach
    void setUp() throws Exception {
        String registered = register(mvc, "me@example.com", "DRIVER");
        auth = bearer(accessToken(registered));
        refresh = refreshToken(registered);
        emails.clear();
    }

    @Test
    void returnsCurrentUser() throws Exception {
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("me@example.com"))
                .andExpect(jsonPath("$.role").value("DRIVER"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void updatesNameAndClearsPhone() throws Exception {
        mvc.perform(patch("/api/v1/me").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Ravi K","phone":""}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ravi K"))
                .andExpect(jsonPath("$.phone").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void rejectsInvalidPhone() throws Exception {
        mvc.perform(patch("/api/v1/me").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"123"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("phone"));
    }

    @Test
    void rejectsWhitespaceOnlyName() throws Exception {
        mvc.perform(patch("/api/v1/me").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"   "}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
    }

    @Test
    void changePasswordRequiresCurrentPassword() throws Exception {
        mvc.perform(post("/api/v1/me/password").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"wrong1234","newPassword":"another123"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WRONG_PASSWORD"));
    }

    @Test
    void changePasswordReturnsFreshSessionAndRevokesTheOldOne() throws Exception {
        String body = mvc.perform(post("/api/v1/me/password").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"%s","newPassword":"another123"}""".formatted(PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").isNumber())
                .andExpect(jsonPath("$.user.email").value("me@example.com"))
                .andReturn().getResponse().getContentAsString();
        String newRefresh = refreshToken(body);
        assertThat(newRefresh).isNotEqualTo(refresh);

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"me@example.com","password":"another123"}"""))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(refresh)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshTokenFromPasswordChangeStillWorks() throws Exception {
        String body = mvc.perform(post("/api/v1/me/password").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"%s","newPassword":"another123"}""".formatted(PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(refreshToken(body))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void resendVerificationSendsEmailUntilVerified() throws Exception {
        mvc.perform(post("/api/v1/me/resend-verification").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isAccepted());
        assertThat(emails.sentTo("me@example.com")).hasSize(1);

        users.findByEmail("me@example.com").orElseThrow().setEmailVerified(true);
        mvc.perform(post("/api/v1/me/resend-verification").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_VERIFIED"));
    }
}
