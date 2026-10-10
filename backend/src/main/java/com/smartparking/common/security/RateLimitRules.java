package com.smartparking.common.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.springframework.util.AntPathMatcher;

/** The route groups that are throttled per client IP (spec sections 9 and 23). */
public final class RateLimitRules {

    private static final AntPathMatcher PATHS = new AntPathMatcher();
    private static final String BUSY = "Too many requests. Please slow down and try again in a minute.";

    private RateLimitRules() {
    }

    /** Auth endpoints, except refresh and logout: brute-force protection. */
    public static RateLimitRule auth(int perMinute) {
        return new RateLimitRule("auth", request -> {
            String uri = request.getRequestURI();
            return uri.startsWith("/api/v1/auth/")
                    // Refresh tokens are 256-bit random values, so these two cannot be brute-forced; limiting
                    // them would only lock out legitimate sessions that share an IP (and the login budget).
                    && !uri.equals("/api/v1/auth/refresh")
                    && !uri.equals("/api/v1/auth/logout");
        }, perMinute, "Too many attempts. Please wait a minute and try again.");
    }

    /** Anonymous reads that hit the database hardest: search, listing detail, quote, availability, reviews. */
    public static RateLimitRule publicReads(int perMinute) {
        return new RateLimitRule("public-reads", request -> readsOnly(request) && matchesAny(request,
                "/api/v1/search",
                "/api/v1/listings/*",
                "/api/v1/listings/*/quote",
                "/api/v1/listings/*/availability",
                "/api/v1/listings/*/reviews"), perMinute, BUSY);
    }

    /** Anything that stores a file or free text: listing photos, owner documents, disputes and their responses. */
    public static RateLimitRule uploadsAndDisputes(int perMinute) {
        return new RateLimitRule("uploads-and-disputes", request -> "POST".equals(request.getMethod())
                && matchesAny(request,
                "/api/v1/owner/listings/*/photos",
                "/api/v1/owner/verification",
                "/api/v1/bookings/*/disputes",
                "/api/v1/owner/disputes/*/respond"), perMinute, BUSY);
    }

    public static RateLimitRule paymentVerify(int perMinute) {
        return new RateLimitRule("payment-verify", request -> "POST".equals(request.getMethod())
                && matchesAny(request, "/api/v1/payments/verify"), perMinute, BUSY);
    }

    public static List<RateLimitRule> standard(int authPerMinute, SecurityProperties.RateLimit limits) {
        return List.of(
                auth(authPerMinute),
                publicReads(limits.publicPerMinute()),
                uploadsAndDisputes(limits.uploadPerMinute()),
                paymentVerify(limits.paymentVerifyPerMinute()));
    }

    private static boolean readsOnly(HttpServletRequest request) {
        return Set.of("GET", "HEAD").contains(request.getMethod());
    }

    private static boolean matchesAny(HttpServletRequest request, String... patterns) {
        String uri = request.getRequestURI();
        for (String pattern : patterns) {
            if (PATHS.match(pattern, uri)) {
                return true;
            }
        }
        return false;
    }
}
