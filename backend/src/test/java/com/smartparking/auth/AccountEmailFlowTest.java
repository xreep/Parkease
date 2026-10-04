package com.smartparking.auth;

import static com.smartparking.support.AuthTestSupport.PASSWORD;
import static com.smartparking.support.AuthTestSupport.refreshToken;
import static com.smartparking.support.AuthTestSupport.register;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.email.EmailMessage;
import com.smartparking.support.IntegrationTest;
import com.smartparking.support.RecordingEmailSender;
import com.smartparking.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class AccountEmailFlowTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    RecordingEmailSender emails;

    @Autowired
    UserRepository users;

    @BeforeEach
    void clearMailbox() {
        emails.clear();
    }

    ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void registrationSendsVerificationLinkThatVerifiesOnce() throws Exception {
        register(mvc, "meera@example.com", "DRIVER");
        EmailMessage mail = emails.lastTo("meera@example.com");
        assertThat(mail.subject()).contains("Verify");
        assertThat(mail.textBody()).contains("http://localhost:5173/verify-email?token=");
        String token = RecordingEmailSender.tokenFrom(mail);

        postJson("/api/v1/auth/verify-email", """
                {"token":"%s"}""".formatted(token)).andExpect(status().isNoContent());

        assertThat(users.findByEmail("meera@example.com").orElseThrow().isEmailVerified()).isTrue();
        postJson("/api/v1/auth/verify-email", """
                {"token":"%s"}""".formatted(token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void forgotPasswordForUnknownEmailIsAcceptedSilently() throws Exception {
        postJson("/api/v1/auth/forgot-password", """
                {"email":"nobody@example.com"}""").andExpect(status().isAccepted());

        assertThat(emails.sentTo("nobody@example.com")).isEmpty();
    }

    @Test
    void resetPasswordChangesPasswordAndEndsSessions() throws Exception {
        String oldRefresh = refreshToken(register(mvc, "reset@example.com", "DRIVER"));
        postJson("/api/v1/auth/forgot-password", """
                {"email":"Reset@Example.com"}""").andExpect(status().isAccepted());
        EmailMessage mail = emails.lastTo("reset@example.com");
        assertThat(mail.subject()).contains("Reset");
        String token = RecordingEmailSender.tokenFrom(mail);

        postJson("/api/v1/auth/reset-password", """
                {"token":"%s","password":"newpass123"}""".formatted(token)).andExpect(status().isNoContent());

        postJson("/api/v1/auth/login", """
                {"email":"reset@example.com","password":"%s"}""".formatted(PASSWORD))
                .andExpect(status().isUnauthorized());
        postJson("/api/v1/auth/login", """
                {"email":"reset@example.com","password":"newpass123"}""")
                .andExpect(status().isOk());
        postJson("/api/v1/auth/refresh", """
                {"refreshToken":"%s"}""".formatted(oldRefresh))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void usingOneResetLinkInvalidatesTheOtherOutstandingLinks() throws Exception {
        register(mvc, "twice@example.com", "DRIVER");
        postJson("/api/v1/auth/forgot-password", """
                {"email":"twice@example.com"}""").andExpect(status().isAccepted());
        String first = RecordingEmailSender.tokenFrom(emails.lastTo("twice@example.com"));
        postJson("/api/v1/auth/forgot-password", """
                {"email":"twice@example.com"}""").andExpect(status().isAccepted());
        String second = RecordingEmailSender.tokenFrom(emails.lastTo("twice@example.com"));
        assertThat(second).isNotEqualTo(first);

        postJson("/api/v1/auth/reset-password", """
                {"token":"%s","password":"newpass123"}""".formatted(second)).andExpect(status().isNoContent());

        postJson("/api/v1/auth/reset-password", """
                {"token":"%s","password":"hijack123"}""".formatted(first))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void resetPasswordRejectsWeakPassword() throws Exception {
        postJson("/api/v1/auth/reset-password", """
                {"token":"whatever","password":"short"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
