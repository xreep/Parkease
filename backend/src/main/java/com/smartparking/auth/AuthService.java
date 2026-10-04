package com.smartparking.auth;

import com.smartparking.auth.dto.AuthResponse;
import com.smartparking.auth.dto.LoginRequest;
import com.smartparking.auth.dto.RegisterRequest;
import com.smartparking.common.error.ApiException;
import com.smartparking.common.security.AuthUser;
import com.smartparking.common.security.JwtProperties;
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
    /** Compared against when the email is unknown so login timing does not reveal which emails exist. */
    private final String dummyHash;

    public AuthService(UserRepository users, OwnerProfileRepository ownerProfiles,
                       RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
                       JwtService jwtService, JwtProperties jwtProperties, Clock clock,
                       AccountTokenService accountTokens) {
        this.users = users;
        this.ownerProfiles = ownerProfiles;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
        this.accountTokens = accountTokens;
        this.dummyHash = passwordEncoder.encode("dummy-password-1");
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
        boolean matches = passwordEncoder.matches(request.password(), hash);
        if (user == null || !matches) {
            throw ApiException.unauthorized("INVALID_CREDENTIALS", "Invalid email or password");
        }
        ensureActive(user);
        return issueTokens(user);
    }

    public AuthResponse refresh(String rawRefreshToken) {
        Instant now = clock.instant();
        RefreshToken token = refreshTokens.findByTokenHash(Tokens.sha256(rawRefreshToken))
                .filter(t -> t.isActive(now))
                .orElseThrow(() -> ApiException.unauthorized("INVALID_REFRESH_TOKEN",
                        "Your session has expired. Please log in again."));
        User user = token.getUser();
        ensureActive(user);
        token.setRevokedAt(now);
        return issueTokens(user);
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
