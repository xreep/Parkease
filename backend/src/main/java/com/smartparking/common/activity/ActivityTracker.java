package com.smartparking.common.activity;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** When someone last used the API. Starts at boot time, so jobs run normally right after a start. */
@Component
public class ActivityTracker {

    private final Clock clock;
    private volatile Instant lastActivity;

    public ActivityTracker(Clock clock) {
        this.clock = clock;
        this.lastActivity = clock.instant();
    }

    public void record() {
        lastActivity = clock.instant();
    }

    public Instant lastActivity() {
        return lastActivity;
    }
}
