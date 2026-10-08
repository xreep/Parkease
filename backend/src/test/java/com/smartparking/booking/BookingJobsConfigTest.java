package com.smartparking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class BookingJobsConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BookingJobsConfig.class);

    @Test
    void schedulingIsOnlyEnabledWhenJobsAreEnabled() {
        runner.withPropertyValues("app.jobs.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class));
        runner.withPropertyValues("app.jobs.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
        runner.run(context -> assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
    }
}
