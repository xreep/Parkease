package com.smartparking.booking;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns the {@code @Scheduled} jobs on only when {@code app.jobs.enabled} is true (it is false in tests). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true")
public class BookingJobsConfig {
}
