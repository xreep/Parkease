package com.smartparking.booking;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.smartparking.common.activity.ActivityProperties;
import com.smartparking.common.activity.ActivityTracker;
import com.smartparking.common.activity.JobGate;
import com.smartparking.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BookingJobSchedulerTest {

    private final MutableClock clock = new MutableClock();
    private final ActivityTracker activity = new ActivityTracker(clock);
    private final BookingJobs jobs = mock(BookingJobs.class);
    private final BookingJobScheduler scheduler = new BookingJobScheduler(jobs,
            new JobGate(activity, clock, new ActivityProperties(Duration.ofMinutes(15), Duration.ofMinutes(60), null)));

    @Test
    void everyTriggerRunsItsJobWhileTheSiteIsInUse() {
        clock.set(Instant.parse("2026-10-12T10:00:00Z"));
        activity.record();
        scheduler.expireHolds();
        scheduler.autoRejectOverdue();
        scheduler.advanceLifecycle();
        scheduler.sendReminders();
        scheduler.retryFailedRefunds();
        scheduler.reconcileRecentPayments();
        scheduler.reconcileOlderPayments();
        verify(jobs).expireHolds();
        verify(jobs).autoRejectOverdue();
        verify(jobs).advanceLifecycle();
        verify(jobs).sendReminders();
        verify(jobs).retryFailedRefunds();
        verify(jobs).reconcileRecentPayments();
        verify(jobs).reconcileOlderPayments();
    }

    @Test
    void theJobsThemselvesHaveNoTimersSoEveryRunGoesThroughTheGate() {
        org.assertj.core.api.Assertions.assertThat(java.util.Arrays.stream(BookingJobs.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(org.springframework.scheduling.annotation.Scheduled.class)))
                .isEmpty();
    }

    @Test
    void aQuietSiteSkipsTheMinuteRunsUntilTheHourlyCatchUp() {
        clock.set(Instant.parse("2026-10-12T10:00:00Z"));
        activity.record();
        scheduler.expireHolds();
        clock.advance(Duration.ofMinutes(20));
        scheduler.expireHolds();
        verify(jobs, org.mockito.Mockito.times(1)).expireHolds();
        clock.advance(Duration.ofMinutes(40));
        scheduler.expireHolds();
        verify(jobs, org.mockito.Mockito.times(2)).expireHolds();
        verify(jobs, never()).sendReminders();
    }
}
