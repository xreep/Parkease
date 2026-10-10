package com.smartparking.admin.audit;

import com.smartparking.common.security.AuthUser;
import com.smartparking.common.web.PageResponse;
import com.smartparking.user.User;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin audit log. {@link #record} joins the caller's transaction, so an action and its audit row commit or roll
 * back together; it must be called from every admin write.
 */
@Service
@RequiredArgsConstructor
public class AdminAuditService {

    static final int DETAILS_MAX = 1000;

    private final AdminActionRepository actions;
    private final EntityManager em;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuthUser admin, String action, String targetType, Long targetId, String details) {
        record(admin.id(), action, targetType, targetId, details);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long adminId, String action, String targetType, Long targetId, String details) {
        AdminAction row = new AdminAction();
        row.setAdmin(em.getReference(User.class, adminId));
        row.setAction(action);
        row.setTargetType(targetType);
        row.setTargetId(targetId);
        row.setDetails(truncate(details));
        actions.save(row);
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminActionDto> list(String action, String targetType, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return PageResponse.from(actions.search(blankToNull(action), blankToNull(targetType), pageable)
                .map(a -> new AdminActionDto(a.getId(), a.getAdmin().getName(), a.getAction(), a.getTargetType(),
                        a.getTargetId(), a.getDetails(), a.getCreatedAt())));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String truncate(String details) {
        if (details == null || details.isBlank()) {
            return null;
        }
        return details.length() <= DETAILS_MAX ? details : details.substring(0, DETAILS_MAX - 1) + "…";
    }
}
