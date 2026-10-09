package com.smartparking.common.security;

import com.smartparking.user.UserStatus;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Turns a valid "Authorization: Bearer <jwt>" header into an authenticated AuthUser principal. */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";
    private static final String AUTH_PATH = "/api/v1/auth/";

    private final JwtService jwtService;
    private final UserStatusCache statusCache;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(PREFIX)) {
            try {
                AuthUser user = jwtService.parseAccessToken(header.substring(PREFIX.length()));
                // Login, refresh and logout check the account themselves, so a suspended user's old token is
                // refused everywhere else: this is what makes a suspension bite within the status cache window.
                if (!request.getRequestURI().startsWith(AUTH_PATH)
                        && statusCache.statusOf(user.id()) == UserStatus.SUSPENDED) {
                    SecurityContextHolder.clearContext();
                    SecurityProblemWriter.write(response, 403, "Forbidden", "ACCOUNT_SUSPENDED",
                            "This account has been suspended. Contact support.");
                    return;
                }
                var authentication = new UsernamePasswordAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException ex) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
