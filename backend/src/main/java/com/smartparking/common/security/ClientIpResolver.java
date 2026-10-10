package com.smartparking.common.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

/**
 * Decides which address a request is attributed to for rate limiting. Behind a trusted proxy that appends the peer it
 * saw to {@code X-Forwarded-For}, the client is the entry {@code hops} places from the right: anything to its left
 * was supplied by the caller and is ignored, so sending a forged header does not change the bucket.
 */
public record ClientIpResolver(boolean trustForwardedFor, int hops) {

    private static final Pattern IP_LITERAL = Pattern.compile("[0-9a-fA-F:.]{2,45}");

    public String resolve(HttpServletRequest request) {
        if (trustForwardedFor) {
            String header = request.getHeader("X-Forwarded-For");
            if (header != null && !header.isBlank()) {
                String[] entries = header.split(",");
                int index = entries.length - Math.max(hops, 1);
                if (index >= 0) {
                    String candidate = entries[index].trim();
                    if (IP_LITERAL.matcher(candidate).matches()) {
                        return candidate;
                    }
                }
            }
        }
        return request.getRemoteAddr();
    }
}
