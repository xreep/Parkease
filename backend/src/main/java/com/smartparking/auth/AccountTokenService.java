package com.smartparking.auth;

import com.smartparking.common.config.AppProperties;
import com.smartparking.common.error.ApiException;
import com.smartparking.common.util.Emails;
import com.smartparking.common.util.Tokens;
import com.smartparking.email.EmailSender;
import com.smartparking.email.EmailTemplates;
import com.smartparking.user.User;
import com.smartparking.user.UserRepository;
import com.smartparking.user.UserStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Single-use emailed tokens: email verification and password reset. */
@Service
@Transactional
@RequiredArgsConstructor
public class AccountTokenService {

    static final Duration VERIFY_TTL = Duration.ofHours(24);
    static final Duration RESET_TTL = Duration.ofMinutes(30);

    private final EmailTokenRepository emailTokens;
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;
    private final AppProperties app;
    private final Clock clock;

    public void sendEmailVerification(User user) {
        String raw = create(user, EmailTokenPurpose.VERIFY_EMAIL, VERIFY_TTL);
        emailSender.send(EmailTemplates.verifyEmail(user, app.frontendUrl() + "/verify-email?token=" + raw));
    }

    public void verifyEmail(String rawToken) {
        consume(rawToken, EmailTokenPurpose.VERIFY_EMAIL).setEmailVerified(true);
    }

    public void requestPasswordReset(String email) {
        users.findByEmail(Emails.normalize(email))
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .ifPresent(user -> {
                    String raw = create(user, EmailTokenPurpose.RESET_PASSWORD, RESET_TTL);
                    emailSender.send(EmailTemplates.resetPassword(user,
                            app.frontendUrl() + "/reset-password?token=" + raw));
                });
    }

    public void resetPassword(String rawToken, String newPassword) {
        User user = consume(rawToken, EmailTokenPurpose.RESET_PASSWORD);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        refreshTokens.revokeAllForUser(user.getId(), clock.instant());
    }

    private String create(User user, EmailTokenPurpose purpose, Duration ttl) {
        String raw = Tokens.newToken();
        EmailToken token = new EmailToken();
        token.setUser(user);
        token.setPurpose(purpose);
        token.setTokenHash(Tokens.sha256(raw));
        token.setExpiresAt(clock.instant().plus(ttl));
        emailTokens.save(token);
        return raw;
    }

    /** Atomically marks the token used (so concurrent requests cannot both succeed) and returns its user. */
    private User consume(String rawToken, EmailTokenPurpose purpose) {
        Instant now = clock.instant();
        EmailToken token = emailTokens.findByTokenHashAndPurpose(Tokens.sha256(rawToken), purpose)
                .orElseThrow(AccountTokenService::invalidToken);
        Long userId = token.getUser().getId();
        if (emailTokens.markUsedIfUsable(token.getId(), now) == 0) {
            throw invalidToken();
        }
        return users.findById(userId).orElseThrow(AccountTokenService::invalidToken);
    }

    private static ApiException invalidToken() {
        return ApiException.badRequest("INVALID_TOKEN", "This link is invalid or has expired");
    }
}
