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
import java.util.List;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-client-IP throttling for the routes named by {@link RateLimitRule}s (brute-force protection on auth, abuse
 * protection on the public and upload endpoints). Each rule keeps its own bucket per IP; the first rule that matches a
 * request decides. In-memory per instance, which is enough for a single backend instance. Answers 429 with a
 * {@code RATE_LIMITED} problem and {@code Retry-After}. CORS preflights are never counted.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private record Limiter(RateLimitRule rule, Cache<String, Bucket> buckets) {
    }

    private final List<Limiter> limiters;
    private final ClientIpResolver clientIps;

    public RateLimitFilter(List<RateLimitRule> rules, ClientIpResolver clientIps) {
        this.limiters = rules.stream()
                .map(rule -> new Limiter(rule, Caffeine.newBuilder()
                        .expireAfterAccess(Duration.ofMinutes(2))
                        .maximumSize(100_000)
                        .build()))
                .toList();
        this.clientIps = clientIps;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        for (Limiter limiter : limiters) {
            if (!limiter.rule().matches().test(request)) {
                continue;
            }
            Bucket bucket = limiter.buckets().get(clientIps.resolve(request), ip -> newBucket(limiter.rule()));
            if (!bucket.tryConsume(1)) {
                response.setHeader("Retry-After", "60");
                SecurityProblemWriter.write(response, 429, "Too Many Requests", "RATE_LIMITED",
                        limiter.rule().message());
                return;
            }
            break;
        }
        chain.doFilter(request, response);
    }

    private static Bucket newBucket(RateLimitRule rule) {
        int perMinute = rule.perMinute();
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(perMinute).refillGreedy(perMinute, Duration.ofMinutes(1)))
                .build();
    }
}
