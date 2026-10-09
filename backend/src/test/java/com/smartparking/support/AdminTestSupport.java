package com.smartparking.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.user.Role;
import com.smartparking.user.User;
import com.smartparking.user.UserRepository;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

public final class AdminTestSupport {

    private AdminTestSupport() {
    }

    /** Creates an ADMIN user through the repository, logs in and returns {@code "Bearer <access>"}. */
    public static String adminAuth(MockMvc mvc, UserRepository users, PasswordEncoder encoder, String email)
            throws Exception {
        User user = TestUsers.newUser(email, Role.ADMIN);
        user.setEmailVerified(true);
        user.setPasswordHash(encoder.encode(AuthTestSupport.PASSWORD));
        users.saveAndFlush(user);
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + AuthTestSupport.PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return AuthTestSupport.bearer(AuthTestSupport.accessToken(body));
    }
}
