package com.smartparking.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartparking.user.Role;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    static final String SECRET = "dGVzdC1vbmx5LWp3dC1zZWNyZXQtc21hcnQtcGFya2luZy1wbGF0Zm9ybS0yMDI2";
    static final String OTHER_SECRET = "ZGV2LW9ubHktand0LXNlY3JldC1zbWFydC1wYXJraW5nLXBsYXRmb3JtLTIwMjYh";
    static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    static final AuthUser OWNER = new AuthUser(42L, "owner@example.com", Role.OWNER);

    static JwtService service(String secret, Instant at) {
        return new JwtService(
                new JwtProperties(secret, Duration.ofMinutes(15), Duration.ofDays(7)),
                Clock.fixed(at, ZoneOffset.UTC));
    }

    @Test
    void createdTokenParsesBackToSameUser() {
        JwtService jwt = service(SECRET, NOW);

        String token = jwt.createAccessToken(OWNER);

        assertThat(jwt.parseAccessToken(token)).isEqualTo(OWNER);
        assertThat(jwt.accessTtlSeconds()).isEqualTo(900);
    }

    @Test
    void expiredTokenIsRejected() {
        String token = service(SECRET, NOW).createAccessToken(OWNER);
        JwtService later = service(SECRET, NOW.plus(Duration.ofMinutes(16)));

        assertThatThrownBy(() -> later.parseAccessToken(token)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        String token = service(OTHER_SECRET, NOW).createAccessToken(OWNER);

        assertThatThrownBy(() -> service(SECRET, NOW).parseAccessToken(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void blankSecretFailsFast() {
        assertThatThrownBy(() -> service("", NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }
}
