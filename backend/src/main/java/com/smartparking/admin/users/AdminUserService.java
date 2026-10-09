package com.smartparking.admin.users;

import com.smartparking.admin.audit.AdminAuditService;
import com.smartparking.auth.RefreshTokenRepository;
import com.smartparking.booking.BookingRepository;
import com.smartparking.common.config.AppProperties;
import com.smartparking.common.error.ApiException;
import com.smartparking.common.security.AuthUser;
import com.smartparking.common.security.UserStatusCache;
import com.smartparking.common.web.PageResponse;
import com.smartparking.email.EmailTemplates;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.notification.NotificationType;
import com.smartparking.notification.Notifier;
import com.smartparking.user.Role;
import com.smartparking.user.User;
import com.smartparking.user.UserRepository;
import com.smartparking.user.UserStatus;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** User moderation: search, suspend and activate. Every write is audited in the same transaction. */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository users;
    private final BookingRepository bookings;
    private final ParkingListingRepository listings;
    private final RefreshTokenRepository refreshTokens;
    private final UserStatusCache statusCache;
    private final AdminAuditService audit;
    private final Notifier notifier;
    private final AppProperties app;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<AdminUserDto> list(Role role, UserStatus status, String q, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<User> result = users.search(role, status, likePattern(q), pageable);
        List<Long> ids = result.getContent().stream().map(User::getId).toList();
        Map<Long, Long> bookingCounts = counts(ids.isEmpty() ? List.of() : bookings.countByDrivers(ids));
        Map<Long, Long> listingCounts = counts(ids.isEmpty() ? List.of() : listings.countByOwners(ids));
        return PageResponse.from(result.map(u -> AdminUserDto.from(u, bookingCounts.getOrDefault(u.getId(), 0L),
                listingCounts.getOrDefault(u.getId(), 0L))));
    }

    @Transactional
    public AdminUserDto suspend(AuthUser admin, Long id, String rawReason) {
        User user = users.findById(id).orElseThrow(() -> ApiException.notFound("User not found"));
        if (user.getRole() == Role.ADMIN || user.getId().equals(admin.id())) {
            throw ApiException.conflict("CANNOT_SUSPEND", "Admins and your own account cannot be suspended");
        }
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw ApiException.conflict("INVALID_STATUS", "This account is already suspended");
        }
        String reason = rawReason.trim();
        user.setStatus(UserStatus.SUSPENDED);
        users.saveAndFlush(user);
        refreshTokens.revokeAllForUser(id, clock.instant()); // clears the persistence context
        statusCache.evictAfterCompletion(id);
        audit.record(admin, "USER_SUSPENDED", "USER", id, "Reason: " + reason);

        User suspended = users.findById(id).orElseThrow();
        notifier.notify(suspended, NotificationType.ACCOUNT_SUSPENDED, "Your account has been suspended",
                "Your account was suspended. Reason: " + reason, null,
                EmailTemplates.accountSuspended(suspended, reason, app.frontendUrl()));
        return dto(suspended);
    }

    @Transactional
    public AdminUserDto activate(AuthUser admin, Long id) {
        User user = users.findById(id).orElseThrow(() -> ApiException.notFound("User not found"));
        if (user.getStatus() != UserStatus.SUSPENDED) {
            throw ApiException.conflict("INVALID_STATUS", "This account is not suspended");
        }
        user.setStatus(UserStatus.ACTIVE);
        users.saveAndFlush(user);
        statusCache.evictAfterCompletion(id);
        audit.record(admin, "USER_ACTIVATED", "USER", id, null);
        return dto(user);
    }

    private AdminUserDto dto(User user) {
        return AdminUserDto.from(user, counts(bookings.countByDrivers(List.of(user.getId()))).getOrDefault(user.getId(), 0L),
                counts(listings.countByOwners(List.of(user.getId()))).getOrDefault(user.getId(), 0L));
    }

    private static Map<Long, Long> counts(List<Object[]> rows) {
        Map<Long, Long> out = new HashMap<>();
        for (Object[] row : rows) {
            out.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return out;
    }

    /** Lower-cased {@code %q%} with LIKE wildcards in the text escaped; blank matches everything. */
    static String likePattern(String q) {
        if (q == null || q.isBlank()) {
            return "%";
        }
        String escaped = q.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
