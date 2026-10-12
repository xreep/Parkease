package com.smartparking.common.activity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Marks the site as in use on every API request. Health checks (the keep-alive ping, the host's probe) are not use,
 * so they keep the server awake without keeping the background jobs, and with them the database, busy.
 */
@Component
public class ActivityFilter extends OncePerRequestFilter {

    private static final String HEALTH = "/api/v1/health";

    private final ActivityTracker activity;

    public ActivityFilter(ActivityTracker activity) {
        this.activity = activity;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/api/") && !path.startsWith(HEALTH)) {
            activity.record();
        }
        chain.doFilter(request, response);
    }
}
