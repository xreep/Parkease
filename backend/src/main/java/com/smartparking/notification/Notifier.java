package com.smartparking.notification;

import com.smartparking.common.util.AfterCommit;
import com.smartparking.email.EmailMessage;
import com.smartparking.email.EmailSender;
import com.smartparking.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The one way to tell a user something happened: records an in-app notification and (optionally) sends the matching
 * email. The notification row is written in the caller's transaction, so it commits or rolls back together with the
 * state change it announces; the email goes out only after that transaction commits (immediately when there is none).
 */
@Component
@RequiredArgsConstructor
public class Notifier {

    static final int TITLE_MAX = 120;
    static final int BODY_MAX = 500;
    static final int LINK_MAX = 300;

    private final NotificationRepository notifications;
    private final EmailSender emailSender;

    /**
     * @param linkPath app-relative path the notification opens (e.g. {@code /driver/bookings/12}); may be null
     * @param email    the email to send alongside; may be null for in-app only. Build it before calling (it may read
     *                 lazy data, which needs the caller's transaction).
     */
    public void notify(User user, NotificationType type, String title, String body, String linkPath,
                       EmailMessage email) {
        Notification notification = new Notification();
        notification.setUser(user);
        notification.setType(type);
        notification.setTitle(truncate(title, TITLE_MAX));
        notification.setBody(truncate(body, BODY_MAX));
        notification.setLink(linkPath == null ? null : truncate(linkPath, LINK_MAX));
        notifications.save(notification);
        if (email != null) {
            AfterCommit.run(() -> emailSender.send(email));
        }
    }

    /** Cuts to {@code max} characters, ending in an ellipsis and never splitting a surrogate pair. */
    static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        int end = max - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "…";
    }
}
