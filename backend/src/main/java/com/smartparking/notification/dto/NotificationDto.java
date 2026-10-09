package com.smartparking.notification.dto;

import com.smartparking.notification.NotificationType;
import java.time.Instant;

public record NotificationDto(Long id, NotificationType type, String title, String body, String link, boolean read,
                              Instant createdAt) {
}
