package com.smartparking.booking;

import com.smartparking.common.activity.JobGate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The timers for {@link BookingJobs}. Each trigger asks the {@link JobGate} first: while the site is in use every job
 * runs on its own interval, while it is quiet each one runs about once an hour and catches up then.
 */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true")
public class BookingJobScheduler {

    private final BookingJobs jobs;
    private final JobGate gate;

    public BookingJobScheduler(BookingJobs jobs, JobGate gate) {
        this.jobs = jobs;
        this.gate = gate;
    }

    @Scheduled(fixedDelay = 60_000)
    public void expireHolds() {
        if (gate.shouldRun("expireHolds")) {
            jobs.expireHolds();
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void autoRejectOverdue() {
        if (gate.shouldRun("autoRejectOverdue")) {
            jobs.autoRejectOverdue();
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void advanceLifecycle() {
        if (gate.shouldRun("advanceLifecycle")) {
            jobs.advanceLifecycle();
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void sendReminders() {
        if (gate.shouldRun("sendReminders")) {
            jobs.sendReminders();
        }
    }

    @Scheduled(fixedDelay = 600_000)
    public void retryFailedRefunds() {
        if (gate.shouldRun("retryFailedRefunds")) {
            jobs.retryFailedRefunds();
        }
    }

    @Scheduled(fixedDelay = 300_000)
    public void reconcileRecentPayments() {
        if (gate.shouldRun("reconcileRecentPayments")) {
            jobs.reconcileRecentPayments();
        }
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void reconcileOlderPayments() {
        if (gate.shouldRun("reconcileOlderPayments")) {
            jobs.reconcileOlderPayments();
        }
    }
}
