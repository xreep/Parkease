package com.smartparking.admin.payouts;

import com.smartparking.admin.audit.AdminAuditService;
import com.smartparking.common.config.AppProperties;
import com.smartparking.common.error.ApiException;
import com.smartparking.common.security.AuthUser;
import com.smartparking.earning.EarningStatus;
import com.smartparking.earning.OwnerEarning;
import com.smartparking.earning.OwnerEarningRepository;
import com.smartparking.email.EmailTemplates;
import com.smartparking.notification.NotificationType;
import com.smartparking.notification.Notifier;
import com.smartparking.owner.OwnerProfile;
import com.smartparking.owner.OwnerProfileRepository;
import com.smartparking.owner.dashboard.OwnerEarningsService;
import com.smartparking.owner.dashboard.dto.OwnerEarningDto;
import com.smartparking.user.Role;
import com.smartparking.user.User;
import com.smartparking.user.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owner payouts: what is waiting, and recording that it was paid (no money moves here). */
@Service
@RequiredArgsConstructor
public class AdminPayoutService {

    private final OwnerEarningRepository earnings;
    private final UserRepository users;
    private final OwnerProfileRepository profiles;
    private final AdminAuditService audit;
    private final Notifier notifier;
    private final AppProperties app;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<PayoutOwnerDto> pending() {
        List<OwnerEarningRepository.PendingTotal> totals = earnings.pendingByOwner();
        List<Long> ids = totals.stream().map(OwnerEarningRepository.PendingTotal::getOwnerId).toList();
        Map<Long, User> owners = new HashMap<>();
        users.findAllById(ids).forEach(u -> owners.put(u.getId(), u));
        Map<Long, OwnerProfile> details = new HashMap<>();
        profiles.findAllById(ids).forEach(p -> details.put(p.getUserId(), p));
        return totals.stream().map(t -> {
            User owner = owners.get(t.getOwnerId());
            OwnerProfile profile = details.get(t.getOwnerId());
            return new PayoutOwnerDto(t.getOwnerId(), owner.getName(), owner.getEmail(), t.getNet(), t.getRows(),
                    method(profile), masked(profile));
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<OwnerEarningDto> pendingEarnings(Long ownerId) {
        User owner = users.findById(ownerId).orElseThrow(() -> ApiException.notFound("Owner not found"));
        if (owner.getRole() != Role.OWNER) {
            throw ApiException.notFound("Owner not found");
        }
        return earnings.findPendingByOwnerId(ownerId).stream().map(OwnerEarningsService::toDto).toList();
    }

    /**
     * Marks the earnings PAID with a payout reference. The rows are locked first; every id must be a PENDING_PAYOUT
     * earning of {@code ownerId} (409 {@code NOTHING_TO_PAY} otherwise, and nothing changes), so two admins paying the
     * same earnings at once cannot both succeed.
     */
    @Transactional
    public MarkPaidResult markPaid(AuthUser admin, MarkPaidRequest request) {
        String reference = request.reference().trim();
        if (reference.length() < 3) {
            throw ApiException.badRequest("VALIDATION_FAILED", "The reference must be at least 3 characters");
        }
        List<Long> ids = List.copyOf(new TreeSet<>(request.earningIds()));
        List<OwnerEarning> locked = earnings.lockAllById(ids);
        boolean allPayable = locked.size() == ids.size() && locked.stream().allMatch(e ->
                e.getStatus() == EarningStatus.PENDING_PAYOUT && e.getOwner().getId().equals(request.ownerId()));
        if (!allPayable) {
            throw ApiException.conflict("NOTHING_TO_PAY",
                    "Every selected earning must be a pending payout of this owner");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (OwnerEarning e : locked) {
            e.setStatus(EarningStatus.PAID);
            e.setPaidAt(clock.instant());
            e.setPayoutReference(reference);
            total = total.add(e.getNet());
        }
        earnings.flush();
        BigDecimal paid = total.setScale(2);
        audit.record(admin, "PAYOUT_MARKED_PAID", "OWNER", request.ownerId(),
                locked.size() + " earnings, ₹" + paid.toPlainString() + ", reference " + reference);

        User owner = locked.get(0).getOwner();
        notifier.notify(owner, NotificationType.PAYOUT_SENT, "Payout sent",
                "₹" + paid.toPlainString() + " for " + locked.size() + (locked.size() == 1 ? " booking" : " bookings")
                        + " has been paid out. Reference: " + reference, "/owner/earnings",
                EmailTemplates.payoutSent(owner, paid.toPlainString(), locked.size(), reference,
                        app.frontendUrl() + "/owner/earnings"));
        return new MarkPaidResult(locked.size(), paid);
    }

    // ---- masking ------------------------------------------------------------------------------------------

    static String method(OwnerProfile p) {
        if (p == null) {
            return null;
        }
        if (hasText(p.getPayoutUpi())) {
            return "UPI";
        }
        return hasText(p.getPayoutBankAccount()) && hasText(p.getPayoutIfsc()) ? "BANK" : null;
    }

    static String masked(OwnerProfile p) {
        String method = method(p);
        if (method == null) {
            return null;
        }
        if (method.equals("UPI")) {
            String upi = p.getPayoutUpi().trim();
            int at = upi.indexOf('@');
            String local = at < 0 ? upi : upi.substring(0, at);
            String handle = at < 0 ? "" : upi.substring(at);
            // Short ids give away proportionally more: reveal one character up to three, two beyond that.
            return local.substring(0, Math.min(local.length() <= 3 ? 1 : 2, local.length())) + "***" + handle;
        }
        String account = p.getPayoutBankAccount().trim();
        String last4 = account.substring(Math.max(0, account.length() - 4));
        return "XXXX" + last4 + " · " + p.getPayoutIfsc().trim().toUpperCase();
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
