package com.smartparking.common.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-client-IP throttling for the routes named by {@link RateLimitRule}s (brute-force protection on auth, abuse
 * protection on the public and upload endpoints). Each rule keeps its own bucket per IP; the first rule that matches a
 * request decides. In-memory per instance, which is enough for a single backend instance. Answers 429 with a
 * {@code RATE_LIMITED} problem and {@code Retry-After}. CORS preflights are never counted.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private record Limiter(RateLimitRule rule, Cache<String, Bucket> buckets) {
    }

    private final List<Limiter> limiters;

    public RateLimitFilter(List<RateLimitRule> rules) {
        this.limiters = rules.stream()
                .map(rule -> new Limiter(rule, Caffeine.newBuilder()
                        .expireAfterAccess(Duration.ofMinutes(2))
                        .maximumSize(20_000)
                        .build()))
                .toList();
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
            // getRemoteAddr() is already the real client when the connection came from a trusted proxy: Tomcat's
            // RemoteIpValve (server.forward-headers-strategy=native) applies X-Forwarded-For only for internal proxies.
            String client = request.getRemoteAddr();
            Bucket bucket = limiter.buckets().get(client, ip -> newBucket(limiter.rule()));
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
            if (!probe.isConsumed()) {
                long seconds = Math.max(1, Math.min(60, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L));
                log.info("Rate limited client={} rule={} method={} path={}", client, limiter.rule().name(),
                        request.getMethod(), request.getRequestURI());
                response.setHeader("Retry-After", Long.toString(seconds));
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
