package com.smartparking.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestPropertySource;
import com.smartparking.support.IntegrationTest;

/** The deployment-relevant settings that live in the YAML files, pinned so a refactor cannot silently drop them. */
@IntegrationTest
@TestPropertySource(properties = "PORT=18123")
class DeploymentConfigFilesTest {

    @Autowired
    Environment environment;

    private static PropertySource<?> load(String file) throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
        assertThat(sources).hasSize(1);
        return sources.get(0);
    }

    @Test
    void theServerListensOnThePortTheHostAssigns() {
        assertThat(environment.getProperty("server.port")).isEqualTo("18123");
    }

    @Test
    void thePortDefaultsTo8080() throws Exception {
        assertThat(load("application.yml").getProperty("server.port")).isEqualTo("${PORT:8080}");
    }

    @Test
    void requestSizeLimitsAreSet() throws Exception {
        PropertySource<?> base = load("application.yml");

        assertThat(base.getProperty("spring.servlet.multipart.max-file-size")).isEqualTo("5MB");
        assertThat(base.getProperty("app.security.max-json-body-bytes")).isEqualTo(1_048_576);
    }

    @Test
    void errorsAndActuatorLeakNothing() throws Exception {
        PropertySource<?> base = load("application.yml");

        assertThat(base.getProperty("server.error.include-stacktrace")).isEqualTo("never");
        assertThat(base.getProperty("server.error.include-message")).isEqualTo("never");
        assertThat(base.getProperty("server.error.include-exception")).isEqualTo(false);
        assertThat(base.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
        assertThat(base.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
        assertThat(base.getProperty("management.endpoint.health.show-components")).isEqualTo("never");
    }

    @Test
    void productionProfileIsHardened() throws Exception {
        PropertySource<?> prod = load("application-prod.yml");

        assertThat(prod.getProperty("app.security.hsts")).isEqualTo(true);
        assertThat(prod.getProperty("app.security.trust-forwarded-for")).isEqualTo(true);
        assertThat(prod.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo(5);
        assertThat(prod.getProperty("app.payments.mock-enabled")).isEqualTo(false);
        assertThat(prod.getProperty("app.frontend-url")).isEqualTo("${FRONTEND_URL}");
        assertThat(prod.getProperty("app.cors-allowed-origins")).isEqualTo("${CORS_ALLOWED_ORIGINS:${FRONTEND_URL}}");
        assertThat(prod.getProperty("app.jwt.secret")).isEqualTo("${JWT_SECRET}");
        // Render/Neon: forwarded headers are read by the rate limiter itself, not rewritten by the container
        assertThat(prod.getProperty("server.forward-headers-strategy")).isEqualTo("none");
        // graceful shutdown for rolling deploys
        assertThat(prod.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(prod.getProperty("logging.level.root")).isEqualTo("INFO");
    }
}
