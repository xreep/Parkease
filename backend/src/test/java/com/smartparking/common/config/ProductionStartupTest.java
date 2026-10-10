package com.smartparking.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartparking.SmartParkingApiApplication;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Boots the whole application with the real {@code prod} profile against the test database, to prove the production
 * checks are wired in (a weak secret stops the context) and that a sound configuration does start.
 */
class ProductionStartupTest {

    private static final String DB = System.getenv().getOrDefault("TEST_DB_URL",
            "jdbc:postgresql://localhost:5432/smartparking_test");

    private static List<String> args(String jwtSecret) {
        List<String> args = new ArrayList<>(List.of(
                "--DB_URL=" + DB,
                "--DB_USERNAME=" + System.getenv().getOrDefault("DB_USERNAME", System.getProperty("user.name")),
                "--DB_PASSWORD=" + System.getenv().getOrDefault("DB_PASSWORD", ""),
                "--FRONTEND_URL=https://parkease.example.com",
                "--RAZORPAY_KEY_ID=rzp_test_placeholder",
                "--RAZORPAY_KEY_SECRET=placeholder",
                "--app.jobs.enabled=false",
                "--server.port=0",
                "--spring.main.banner-mode=off"));
        if (jwtSecret != null) {
            args.add("--JWT_SECRET=" + jwtSecret);
        }
        return args;
    }

    private static ConfigurableApplicationContext run(String jwtSecret) {
        return new SpringApplicationBuilder(SmartParkingApiApplication.class)
                .profiles("prod")
                .web(WebApplicationType.SERVLET)
                .run(args(jwtSecret).toArray(String[]::new));
    }

    @Test
    void productionRefusesToStartWithTheDevelopmentJwtSecret() {
        assertThatThrownBy(() -> run(ProductionConfigValidatorTest.DEV_SECRET).close())
                .hasStackTraceContaining("Unsafe production configuration")
                .hasStackTraceContaining("JWT_SECRET");
    }

    @Test
    void productionRefusesToStartWithAShortJwtSecret() {
        assertThatThrownBy(() -> run("c2VjcmV0").close())
                .hasStackTraceContaining("Unsafe production configuration")
                .hasStackTraceContaining("32 bytes");
    }

    @Test
    void productionRefusesToStartWithoutAJwtSecret() {
        assertThatThrownBy(() -> run(null).close())
                .hasStackTraceContaining("Unsafe production configuration")
                .hasStackTraceContaining("JWT_SECRET must be set");
    }

    @Test
    void productionStartsWithASoundConfiguration() {
        try (ConfigurableApplicationContext context = run(ProductionConfigValidatorTest.strongSecret())) {
            assertThat(context.isRunning()).isTrue();
            assertThat(context.getEnvironment().getProperty("app.security.hsts", Boolean.class)).isTrue();
            assertThat(context.getEnvironment().getProperty("app.security.trust-forwarded-for", Boolean.class))
                    .isTrue();
            // CORS follows FRONTEND_URL unless CORS_ALLOWED_ORIGINS overrides it
            assertThat(context.getEnvironment().getProperty("app.cors-allowed-origins"))
                    .isEqualTo("https://parkease.example.com");
        }
    }
}
