package com.smartparking.common.activity;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JobGateTest {

    private final MutableClock clock = new MutableClock();
    private final ActivityTracker activity = new ActivityTracker(clock);
    private final JobGate gate = new JobGate(activity, clock,
            new ActivityProperties(Duration.ofMinutes(15), Duration.ofMinutes(60), null));

    @Test
    void runsEveryTimeWhileTheSiteIsInUse() {
        clock.set(Instant.parse("2026-10-12T10:00:00Z"));
        activity.record();
        assertThat(gate.shouldRun("expire")).isTrue();
        clock.advance(Duration.ofMinutes(1));
        assertThat(gate.shouldRun("expire")).isTrue();
        clock.advance(Duration.ofMinutes(13));
        assertThat(gate.shouldRun("expire")).isTrue();
    }

    @Test
    void runsOnlyHourlyOnceTheSiteHasBeenQuiet() {
        clock.set(Instant.parse("2026-10-12T10:00:00Z"));
        activity.record();
        assertThat(gate.shouldRun("expire")).isTrue();

        clock.advance(Duration.ofMinutes(16));           // quiet for 16 minutes
        assertThat(gate.shouldRun("expire")).isFalse();  // last run 16 minutes ago
        clock.advance(Duration.ofMinutes(44));           // 60 minutes after the last run
        assertThat(gate.shouldRun("expire")).isTrue();
        clock.advance(Duration.ofMinutes(1));
        assertThat(gate.shouldRun("expire")).isFalse();
    }

    @Test
    void eachJobHasItsOwnHourlyClock() {
        clock.set(Instant.parse("2026-10-12T10:00:00Z"));
        activity.record();
        assertThat(gate.shouldRun("expire")).isTrue();
        clock.advance(Duration.ofMinutes(30));
        assertThat(gate.shouldRun("reminders")).isTrue();   // never ran before: catches up once
        assertThat(gate.shouldRun("expire")).isFalse();
    }

    @Test
    void aVisitorAfterAQuietSpellResumesEveryMinuteRuns() {
        clock.set(Instant.parse("2026-10-12T10:00:00Z"));
        activity.record();
        gate.shouldRun("expire");
        clock.advance(Duration.ofMinutes(20));
        assertThat(gate.shouldRun("expire")).isFalse();
        activity.record();
        assertThat(gate.shouldRun("expire")).isTrue();
    }

    @Test
    void jobsRunRightAfterStartupBeforeAnyVisitor() {
        assertThat(gate.shouldRun("expire")).isTrue();
    }
}
