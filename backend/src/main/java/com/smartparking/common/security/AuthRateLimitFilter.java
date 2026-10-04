package com.smartparking.common.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limits /api/v1/auth/** (except refresh and logout) to N requests per minute per client IP (brute-force protection).
 * In-memory per instance; good enough for a single backend instance.
 */
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private final int perMinute;
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(2))
            .maximumSize(100_000)
            .build();

    public AuthRateLimitFilter(int perMinute) {
        this.perMinute = perMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || !uri.startsWith("/api/v1/auth/")
                // Refresh tokens are 256-bit random values, so these two cannot be brute-forced; limiting
                // them would only lock out legitimate sessions that share an IP (and the login budget).
                || uri.equals("/api/v1/auth/refresh")
                || uri.equals("/api/v1/auth/logout");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Bucket bucket = buckets.get(request.getRemoteAddr(), ip -> newBucket());
        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("Retry-After", "60");
        SecurityProblemWriter.write(response, 429, "Too Many Requests", "RATE_LIMITED",
                "Too many attempts. Please wait a minute and try again.");
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(perMinute).refillGreedy(perMinute, Duration.ofMinutes(1)))
                .build();
    }
}
