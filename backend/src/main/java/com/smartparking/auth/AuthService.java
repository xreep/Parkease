package com.smartparking.auth;

import com.smartparking.auth.dto.AuthResponse;
import com.smartparking.auth.dto.LoginRequest;
import com.smartparking.auth.dto.RegisterRequest;
import com.smartparking.user.ChangePasswordRequest;
import com.smartparking.common.error.ApiException;
import com.smartparking.common.security.AuthUser;
import com.smartparking.common.security.JwtProperties;
import com.smartparking.common.seed.DemoMode;
import com.smartparking.common.security.JwtService;
import com.smartparking.common.util.Emails;
import com.smartparking.common.util.Tokens;
import com.smartparking.owner.OwnerProfile;
import com.smartparking.owner.OwnerProfileRepository;
import com.smartparking.user.Role;
import com.smartparking.user.User;
import com.smartparking.user.UserDto;
import com.smartparking.user.UserRepository;
import com.smartparking.user.UserStatus;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AuthService {

    private final UserRepository users;
    private final OwnerProfileRepository ownerProfiles;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final Clock clock;
    private final AccountTokenService accountTokens;
    private final DemoMode demoMode;
    /** BCrypt only uses the first 72 bytes and rejects longer input when hashing. */
    private static final int MAX_PASSWORD_BYTES = 72;

    /** Compared against when the email is unknown so login timing does not reveal which emails exist. */
    private static final String DUMMY_PASSWORD = "dummy-password-1";

    private final String dummyHash;

    public AuthService(UserRepository users, OwnerProfileRepository ownerProfiles,
                       RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
                       JwtService jwtService, JwtProperties jwtProperties, Clock clock,
                       AccountTokenService accountTokens, DemoMode demoMode) {
        this.users = users;
        this.ownerProfiles = ownerProfiles;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
        this.accountTokens = accountTokens;
        this.demoMode = demoMode;
        this.dummyHash = passwordEncoder.encode(DUMMY_PASSWORD);
    }

    public AuthResponse register(RegisterRequest request) {
        if (request.role() == Role.ADMIN) {
            throw ApiException.badRequest("INVALID_ROLE", "You can register as a driver or a parking owner");
        }
        String email = Emails.normalize(request.email());
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists");
        }
        User user = new User();
        user.setName(request.name().trim());
        user.setEmail(email);
        user.setPhone(request.phone());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(request.role());
        users.save(user);
        if (user.getRole() == Role.OWNER) {
            ownerProfiles.save(OwnerProfile.forUser(user));
        }
        accountTokens.sendEmailVerification(user);
        return issueTokens(user);
    }

    public AuthResponse login(LoginRequest request) {
        User user = users.findByEmail(Emails.normalize(request.email())).orElse(null);
        String hash = user != null ? user.getPasswordHash() : dummyHash;
        // An over-long password can never be a real one; still pay for a hash comparison to keep timing uniform.
        boolean tooLong = request.password().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES;
        boolean matches = passwordEncoder.matches(tooLong ? DUMMY_PASSWORD : request.password(), hash) && !tooLong;
        if (user == null || !matches) {
            throw ApiException.unauthorized("INVALID_CREDENTIALS", "Invalid email or password");
        }
        ensureActive(user);
        return issueTokens(user);
    }

    /**
     * Changing the password ends every session, including the caller's, and outstanding reset links die too,
     * so the caller gets a fresh session back instead of being logged out when the access token expires.
     */
    public AuthResponse changePassword(Long userId, ChangePasswordRequest request) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User not found"));
        if (demoMode.isLockedAccount(user.getEmail())) {
            throw ApiException.forbidden("DEMO_ACCOUNT_LOCKED",
                    "This is a shared demo account, so its password cannot be changed. Create your own account to try it.");
        }
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("WRONG_PASSWORD", "Your current password is incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        refreshTokens.revokeAllForUser(userId, clock.instant());
        accountTokens.invalidateResetLinks(userId);
        return issueTokens(user);
    }

    /** noRollbackFor: the reuse-detection revocation must persist even though a 401 is thrown. */
    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse refresh(String rawRefreshToken) {
        Instant now = clock.instant();
        RefreshToken token = refreshTokens.findByTokenHash(Tokens.sha256(rawRefreshToken))
                .orElseThrow(AuthService::invalidRefreshToken);
        Long userId = token.getUser().getId();
        if (token.getRevokedAt() != null) {
            // A rotated/revoked token is being presented again: assume theft and end every session.
            refreshTokens.revokeAllForUser(userId, now);
            throw invalidRefreshToken();
        }
        ensureActive(token.getUser());
        if (refreshTokens.revokeIfActive(token.getId(), now) == 0) {
            throw invalidRefreshToken();
        }
        User user = users.findById(userId).orElseThrow(AuthService::invalidRefreshToken);
        return issueTokens(user);
    }

    private static ApiException invalidRefreshToken() {
        return ApiException.unauthorized("INVALID_REFRESH_TOKEN", "Your session has expired. Please log in again.");
    }

    public void logout(String rawRefreshToken) {
        refreshTokens.findByTokenHash(Tokens.sha256(rawRefreshToken))
                .filter(t -> t.getRevokedAt() == null)
                .ifPresent(t -> t.setRevokedAt(clock.instant()));
    }

    private void ensureActive(User user) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw ApiException.forbidden("ACCOUNT_SUSPENDED", "This account has been suspended. Contact support.");
        }
    }

    private AuthResponse issueTokens(User user) {
        String raw = Tokens.newToken();
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setTokenHash(Tokens.sha256(raw));
        refreshToken.setExpiresAt(clock.instant().plus(jwtProperties.refreshTtl()));
        refreshTokens.save(refreshToken);
        return new AuthResponse(
                jwtService.createAccessToken(AuthUser.from(user)),
                raw,
                jwtService.accessTtlSeconds(),
                UserDto.from(user));
    }
}
