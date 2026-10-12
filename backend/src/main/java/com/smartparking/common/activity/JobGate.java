package com.smartparking.common.activity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Decides whether a scheduled job runs now: always while the site is in use, otherwise at most once per idle interval
 * per job. The jobs catch up on whatever they skipped, so a quiet site only sees its bookings update a little later,
 * and a serverless database gets to sleep between the hourly runs.
 */
@Component
public class JobGate {

    private final ActivityTracker activity;
    private final Clock clock;
    private final Duration activeWindow;
    private final Duration idleInterval;
    private final Map<String, Instant> lastRuns = new ConcurrentHashMap<>();

    public JobGate(ActivityTracker activity, Clock clock, ActivityProperties properties) {
        this.activity = activity;
        this.clock = clock;
        this.activeWindow = properties.activeWindow();
        this.idleInterval = properties.idleInterval();
    }

    public boolean shouldRun(String job) {
        Instant now = clock.instant();
        boolean inUse = !activity.lastActivity().plus(activeWindow).isBefore(now);
        Instant last = lastRuns.get(job);
        if (inUse || last == null || !last.plus(idleInterval).isAfter(now)) {
            lastRuns.put(job, now);
            return true;
        }
        return false;
    }
}
