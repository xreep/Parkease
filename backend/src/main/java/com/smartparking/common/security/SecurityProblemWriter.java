package com.smartparking.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;

/** Writes ProblemDetail-shaped JSON from servlet filters, where @RestControllerAdvice does not apply. */
public final class SecurityProblemWriter {

    private SecurityProblemWriter() {
    }

    public static void unauthorized(HttpServletRequest request, HttpServletResponse response,
                                    AuthenticationException ex) throws IOException {
        write(response, 401, "Unauthorized", "UNAUTHORIZED", "Authentication is required to access this resource");
    }

    public static void forbidden(HttpServletRequest request, HttpServletResponse response,
                                 AccessDeniedException ex) throws IOException {
        write(response, 403, "Forbidden", "FORBIDDEN", "You do not have permission to perform this action");
    }

    public static void write(HttpServletResponse response, int status, String title, String code, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"code\":\"%s\",\"detail\":\"%s\"}"
                        .formatted(title, status, code, detail));
    }
}
