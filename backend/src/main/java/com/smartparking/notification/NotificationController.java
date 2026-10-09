package com.smartparking.notification;

import com.smartparking.common.security.AuthUser;
import com.smartparking.common.web.PageResponse;
import com.smartparking.notification.dto.NotificationDto;
import com.smartparking.notification.dto.UnreadCountDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Open to every signed-in user (drivers, owners and admins alike); each sees only their own. */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService service;

    @GetMapping
    public PageResponse<NotificationDto> list(@AuthenticationPrincipal AuthUser principal,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return service.list(principal.id(), page, size);
    }

    @GetMapping("/unread-count")
    public UnreadCountDto unreadCount(@AuthenticationPrincipal AuthUser principal) {
        return service.unreadCount(principal.id());
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id) {
        service.markRead(principal.id(), id);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@AuthenticationPrincipal AuthUser principal) {
        service.markAllRead(principal.id());
    }
}
