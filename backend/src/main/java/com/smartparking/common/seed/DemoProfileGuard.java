package com.smartparking.common.seed;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Stops the application from starting under the {@code demo} profile without {@code DEMO_PASSWORD}. Every seeded
 * account logs in with that password, so a hosted demo must set its own; the dev profile's well-known default must not
 * leak in, whichever order the profiles are listed in.
 */
@Component
@Profile("demo")
class DemoProfileGuard {

    static final String MESSAGE = "DEMO_PASSWORD must be set when the demo profile is active "
            + "(every seeded account logs in with it)";

    DemoProfileGuard(Environment environment) {
        if (!StringUtils.hasText(environment.getProperty("DEMO_PASSWORD"))) {
            throw new IllegalStateException(MESSAGE);
        }
    }
}
