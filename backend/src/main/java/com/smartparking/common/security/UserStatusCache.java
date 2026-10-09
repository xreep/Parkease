package com.smartparking.common.security;

import com.smartparking.user.UserRepository;
import com.smartparking.user.UserStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Account status per user id, so the JWT filter does not query the database on every request. Entries live at most
 * {@value #TTL_SECONDS} seconds, which bounds how long a suspension (made through another instance, or straight in
 * the database) can go unnoticed; suspending or activating through the admin API evicts the entry at once.
 */
@Component
public class UserStatusCache {

    static final long TTL_SECONDS = 45;
    private static final int MAX_ENTRIES = 50_000;

    private record Entry(UserStatus status, Instant expiresAt) {
    }

    private final UserRepository users;
    private final Clock clock;
    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();

    public UserStatusCache(UserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    /** The user's status; accounts that no longer exist count as ACTIVE (a stale token is not a suspension). */
    public UserStatus statusOf(Long userId) {
        Instant now = clock.instant();
        Entry cached = entries.get(userId);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.status();
        }
        Optional<UserStatus> found = users.findStatusById(userId);
        if (found.isEmpty()) {
            entries.remove(userId);
            return UserStatus.ACTIVE;
        }
        if (entries.size() >= MAX_ENTRIES) {
            entries.clear();
        }
        entries.put(userId, new Entry(found.get(), now.plus(Duration.ofSeconds(TTL_SECONDS))));
        return found.get();
    }

    public void evict(Long userId) {
        entries.remove(userId);
    }

    /**
     * Evicts now and again when the current transaction finishes, so a request that re-reads the old status between
     * the two cannot keep it cached.
     */
    public void evictAfterCompletion(Long userId) {
        evict(userId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    evict(userId);
                }
            });
        }
    }

    /** Forgets every entry (also the hook tests use to skip the cache window). */
    public void clear() {
        entries.clear();
    }
}
