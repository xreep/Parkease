package com.smartparking.settings;

import com.smartparking.booking.BookingProperties;
import com.smartparking.common.error.ApiException;
import com.smartparking.common.security.AuthUser;
import com.smartparking.admin.audit.AdminAuditService;
import com.smartparking.pricing.PricingProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The platform fee, GST, hold / approval windows and price guidelines that admins can edit at run time. Values come
 * from the {@code platform_settings} table; a key that is missing (or unreadable) falls back to the
 * {@code application.yml} default ({@code app.pricing.*}, {@code app.booking.*}).
 *
 * <p>The table is read once and cached in memory. The cache is dropped when settings are updated (and again once the
 * updating transaction ends, whether it committed or not), so the next read reloads. This assumes a single application
 * instance: a second instance would keep serving its cached values until it restarts.
 */
@Service
public class PlatformSettings {

    private static final Logger log = LoggerFactory.getLogger(PlatformSettings.class);

    static final String FEE = "platform_fee_percent";
    static final String GST = "gst_percent";
    static final String HOLD = "hold_minutes";
    static final String APPROVAL = "approval_hours";
    static final String LEAD = "request_min_lead_minutes";
    static final int TIERS = 3;
    private static final BigDecimal MAX_PRICE = new BigDecimal("100000");

    /** Advisory hourly price range of a city tier. */
    public record PriceGuideline(int tier, BigDecimal minHourly, BigDecimal maxHourly) {
    }

    private final PlatformSettingRepository repository;
    private final PricingProperties pricingDefaults;
    private final BookingProperties bookingDefaults;
    private final AdminAuditService audit;
    private final Clock clock;

    private final Object cacheLock = new Object();
    private final AtomicLong version = new AtomicLong();
    private volatile Map<String, String> cache;

    public PlatformSettings(PlatformSettingRepository repository, PricingProperties pricingDefaults,
                            BookingProperties bookingDefaults, AdminAuditService audit, Clock clock) {
        this.repository = repository;
        this.pricingDefaults = pricingDefaults;
        this.bookingDefaults = bookingDefaults;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- typed getters ------------------------------------------------------------------------------------------

    public BigDecimal platformFeePercent() {
        return decimal(FEE, pricingDefaults.platformFeePercent());
    }

    public BigDecimal gstPercent() {
        return decimal(GST, pricingDefaults.gstPercent());
    }

    public int holdMinutes() {
        return integer(HOLD, bookingDefaults.holdMinutes());
    }

    public int approvalHours() {
        return integer(APPROVAL, bookingDefaults.approvalHours());
    }

    public int requestMinLeadMinutes() {
        return integer(LEAD, bookingDefaults.requestMinLeadMinutes());
    }

    public PriceGuideline priceGuideline(int tier) {
        if (tier < 1 || tier > TIERS) {
            throw new IllegalArgumentException("tier must be 1, 2 or 3");
        }
        DefaultRange d = defaultRange(tier);
        return new PriceGuideline(tier, plain(decimal("price_tier" + tier + "_min", d.min)),
                plain(decimal("price_tier" + tier + "_max", d.max)));
    }

    /** Rejects (400 {@code INVALID_TIME_RANGE}) a start too close for a listing that needs the owner's approval. */
    public void requireNoticeForRequest(boolean autoApprove, Instant start, Instant now) {
        int lead = requestMinLeadMinutes();
        if (!autoApprove && start.isBefore(now.plus(Duration.ofMinutes(lead)))) {
            throw ApiException.badRequest("INVALID_TIME_RANGE",
                    "Request-to-book listings need at least " + lead + " minutes' notice");
        }
    }

    public PlatformSettingsDto current() {
        List<PriceGuidelineDto> guidelines = new ArrayList<>();
        for (int tier = 1; tier <= TIERS; tier++) {
            PriceGuideline g = priceGuideline(tier);
            guidelines.add(new PriceGuidelineDto(tier, g.minHourly(), g.maxHourly()));
        }
        return new PlatformSettingsDto(plain(platformFeePercent()), plain(gstPercent()), holdMinutes(),
                approvalHours(), requestMinLeadMinutes(), guidelines);
    }

    // ---- update -------------------------------------------------------------------------------------------------

    /**
     * Validates and stores the settings, then audits the keys that actually changed (no audit row when nothing did).
     * Existing bookings are untouched: their amounts and deadlines are stored on the booking itself.
     */
    @Transactional
    public PlatformSettingsDto update(AuthUser admin, PlatformSettingsDto request) {
        Map<String, String> wanted = validate(request);
        Map<String, String> before = effective();
        Instant now = clock.instant();
        List<String> changes = new ArrayList<>();
        for (Map.Entry<String, String> e : wanted.entrySet()) {
            String old = before.get(e.getKey());
            if (old != null && sameValue(old, e.getValue())) {
                continue;
            }
            PlatformSetting row = repository.findById(e.getKey()).orElseGet(() -> {
                PlatformSetting s = new PlatformSetting();
                s.setKey(e.getKey());
                return s;
            });
            row.setValue(e.getValue());
            row.setUpdatedAt(now);
            row.setUpdatedBy(admin.id());
            repository.save(row);
            changes.add(e.getKey() + ": " + old + " -> " + e.getValue());
        }
        if (!changes.isEmpty()) {
            repository.flush();
            audit.record(admin, "SETTINGS_UPDATED", "SETTINGS", null, String.join("; ", changes));
        }
        invalidate();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    invalidate();
                }
            });
        }
        return current();
    }

    /** Drops the cached values; the next read reloads them from the database. */
    public void invalidate() {
        synchronized (cacheLock) {
            version.incrementAndGet();
            cache = null;
        }
    }

    // ---- validation ---------------------------------------------------------------------------------------------

    private Map<String, String> validate(PlatformSettingsDto r) {
        Map<String, String> out = new HashMap<>();
        out.put(FEE, range("platformFeePercent", r.platformFeePercent(), BigDecimal.ZERO, new BigDecimal("50")));
        out.put(GST, range("gstPercent", r.gstPercent(), BigDecimal.ZERO, new BigDecimal("28")));
        out.put(HOLD, whole("holdMinutes", r.holdMinutes(), 5, 60));
        out.put(APPROVAL, whole("approvalHours", r.approvalHours(), 1, 24));
        out.put(LEAD, whole("requestMinLeadMinutes", r.requestMinLeadMinutes(), 0, 240));

        Map<Integer, PriceGuidelineDto> byTier = new HashMap<>();
        for (PriceGuidelineDto g : r.priceGuidelines()) {
            if (g.tier() < 1 || g.tier() > TIERS || byTier.put(g.tier(), g) != null) {
                throw invalid("priceGuidelines must contain tiers 1, 2 and 3 exactly once");
            }
        }
        if (byTier.size() != TIERS) {
            throw invalid("priceGuidelines must contain tiers 1, 2 and 3 exactly once");
        }
        for (int tier = 1; tier <= TIERS; tier++) {
            PriceGuidelineDto g = byTier.get(tier);
            checkedMoney("Tier " + tier + " minHourly", g.minHourly());
            checkedMoney("Tier " + tier + " maxHourly", g.maxHourly());
            if (g.minHourly().signum() <= 0 || g.minHourly().compareTo(g.maxHourly()) > 0
                    || g.maxHourly().compareTo(MAX_PRICE) > 0) {
                throw invalid("Tier " + tier + " prices must satisfy 0 < min <= max <= 100000");
            }
            out.put("price_tier" + tier + "_min", canonical(g.minHourly()));
            out.put("price_tier" + tier + "_max", canonical(g.maxHourly()));
        }
        return out;
    }

    /** At most two decimals, so every value fits its column and reads back exactly as typed. */
    private static BigDecimal checkedMoney(String field, BigDecimal v) {
        if (v.stripTrailingZeros().scale() > 2) {
            throw invalid(field + " can have at most two decimals");
        }
        return v;
    }

    private static String range(String field, BigDecimal v, BigDecimal min, BigDecimal max) {
        checkedMoney(field, v);
        if (v.compareTo(min) < 0 || v.compareTo(max) > 0) {
            throw invalid(field + " must be between " + min.toPlainString() + " and " + max.toPlainString());
        }
        return canonical(v);
    }

    private static String whole(String field, int v, int min, int max) {
        if (v < min || v > max) {
            throw invalid(field + " must be between " + min + " and " + max);
        }
        return Integer.toString(v);
    }

    private static ApiException invalid(String message) {
        return ApiException.badRequest("INVALID_SETTING", message);
    }

    // ---- storage ------------------------------------------------------------------------------------------------

    private record DefaultRange(BigDecimal min, BigDecimal max) {
    }

    private static DefaultRange defaultRange(int tier) {
        return switch (tier) {
            case 1 -> new DefaultRange(new BigDecimal("20"), new BigDecimal("150"));
            case 2 -> new DefaultRange(new BigDecimal("10"), new BigDecimal("100"));
            default -> new DefaultRange(new BigDecimal("5"), new BigDecimal("80"));
        };
    }

    /** Stored values with fallbacks filled in, as text. */
    private Map<String, String> effective() {
        Map<String, String> out = new HashMap<>();
        out.put(FEE, canonical(platformFeePercent()));
        out.put(GST, canonical(gstPercent()));
        out.put(HOLD, Integer.toString(holdMinutes()));
        out.put(APPROVAL, Integer.toString(approvalHours()));
        out.put(LEAD, Integer.toString(requestMinLeadMinutes()));
        for (int tier = 1; tier <= TIERS; tier++) {
            PriceGuideline g = priceGuideline(tier);
            out.put("price_tier" + tier + "_min", canonical(g.minHourly()));
            out.put("price_tier" + tier + "_max", canonical(g.maxHourly()));
        }
        return out;
    }

    private Map<String, String> values() {
        Map<String, String> snapshot = cache;
        if (snapshot != null) {
            return snapshot;
        }
        long seen = version.get();
        Map<String, String> loaded = new HashMap<>();
        for (PlatformSetting s : repository.findAll()) {
            loaded.put(s.getKey(), s.getValue());
        }
        synchronized (cacheLock) { // check and set must be atomic with invalidate(), or a stale load could win
            if (version.get() == seen) {
                cache = loaded;
            }
        }
        return loaded;
    }

    private BigDecimal decimal(String key, BigDecimal fallback) {
        String raw = values().get(key);
        if (raw != null) {
            try {
                return new BigDecimal(raw.trim());
            } catch (NumberFormatException e) {
                log.warn("Setting {} has an unreadable value '{}'; using the default", key, raw);
            }
        }
        return fallback;
    }

    private int integer(String key, int fallback) {
        String raw = values().get(key);
        if (raw != null) {
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                log.warn("Setting {} has an unreadable value '{}'; using the default", key, raw);
            }
        }
        return fallback;
    }

    private static boolean sameValue(String a, String b) {
        try {
            return new BigDecimal(a).compareTo(new BigDecimal(b)) == 0;
        } catch (NumberFormatException e) {
            return a.equals(b);
        }
    }

    /** "10", "12.5": no exponent, no trailing zeros. */
    private static String canonical(BigDecimal v) {
        return plain(v).toPlainString();
    }

    private static BigDecimal plain(BigDecimal v) {
        BigDecimal stripped = v.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
