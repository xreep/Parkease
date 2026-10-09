package com.smartparking.admin.audit;

import java.time.Instant;

public record AdminActionDto(Long id, String adminName, String action, String targetType, Long targetId,
                             String details, Instant createdAt) {
}
