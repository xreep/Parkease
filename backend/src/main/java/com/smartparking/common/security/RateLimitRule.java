package com.smartparking.common.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Predicate;

/** One throttled group of routes: every client IP gets {@code perMinute} requests a minute across all its routes. */
public record RateLimitRule(String name, Predicate<HttpServletRequest> matches, int perMinute, String message) {
}
