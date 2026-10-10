package com.smartparking.common.seed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

/** A hosted demo must never come up with a password that is written down in the repository. */
class DemoProfileGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("demo"))
            .withUserConfiguration(DemoProfileGuard.class);

    @Test
    void bootingTheDemoProfileFailsWithoutDemoPassword() {
        runner.withSystemProperties("DEMO_PASSWORD=").run(context -> { // an empty system property beats the shell's
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(DemoProfileGuard.MESSAGE);
        });
    }

    @Test
    void blankOrWhitespaceDoesNotCount() {
        runner.withSystemProperties("DEMO_PASSWORD=   ").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bootsWhenItIsSet() {
        runner.withPropertyValues("DEMO_PASSWORD=Sup3r-secret").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void otherProfilesAreNotAffected() {
        new ApplicationContextRunner().withUserConfiguration(DemoProfileGuard.class)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(DemoProfileGuard.class));
        assertThat(new StandardEnvironment().getActiveProfiles()).doesNotContain("demo");
    }
}
