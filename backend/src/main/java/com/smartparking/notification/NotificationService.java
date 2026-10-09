package com.smartparking.notification;

import com.smartparking.common.error.ApiException;
import com.smartparking.common.web.PageResponse;
import com.smartparking.notification.dto.NotificationDto;
import com.smartparking.notification.dto.UnreadCountDto;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A user's own notifications: someone else's notification is indistinguishable from a missing one (404). */
@Service
@RequiredArgsConstructor
public class NotificationService {

    static final int MAX_PAGE_SIZE = 50;

    private final NotificationRepository notifications;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<NotificationDto> list(Long userId, int page, int size) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageResponse.from(notifications
                .findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(pageNumber, pageSize))
                .map(NotificationService::toDto));
    }

    @Transactional(readOnly = true)
    public UnreadCountDto unreadCount(Long userId) {
        return new UnreadCountDto(notifications.countByUserIdAndReadAtIsNull(userId));
    }

    /** Marks one notification read; reading it again changes nothing. */
    @Transactional
    public void markRead(Long userId, Long id) {
        Notification notification = notifications.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("Notification not found"));
        if (notification.getReadAt() == null) {
            notification.setReadAt(clock.instant());
        }
    }

    @Transactional
    public void markAllRead(Long userId) {
        notifications.markAllRead(userId, clock.instant());
    }

    static NotificationDto toDto(Notification n) {
        return new NotificationDto(n.getId(), n.getType(), n.getTitle(), n.getBody(), n.getLink(),
                n.getReadAt() != null, n.getCreatedAt());
    }
}
