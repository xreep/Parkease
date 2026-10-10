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
        assertThat(prod.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo(5);
        // Neon suspends idle compute and drops connections: keep none idle, retire them well inside its timeouts
        assertThat(prod.getProperty("spring.datasource.hikari.minimum-idle")).isEqualTo(0);
        assertThat(prod.getProperty("spring.datasource.hikari.idle-timeout")).isEqualTo(120000);
        assertThat(prod.getProperty("spring.datasource.hikari.max-lifetime")).isEqualTo(240000);
        assertThat(prod.getProperty("app.payments.mock-enabled")).isEqualTo(false);
        assertThat(prod.getProperty("app.frontend-url")).isEqualTo("${FRONTEND_URL}");
        assertThat(prod.getProperty("app.cors-allowed-origins")).isEqualTo("${CORS_ALLOWED_ORIGINS:${FRONTEND_URL}}");
        assertThat(prod.getProperty("app.jwt.secret")).isEqualTo("${JWT_SECRET}");
        // Render's proxy: Tomcat's RemoteIpValve resolves the client address, configurable via INTERNAL_PROXIES
        assertThat(prod.getProperty("server.forward-headers-strategy")).isEqualTo("native");
        assertThat((String) prod.getProperty("server.tomcat.remoteip.internal-proxies"))
                .startsWith("${INTERNAL_PROXIES:").contains("10\\.").contains("100\\.6[4-9]");
        // graceful shutdown for rolling deploys
        assertThat(prod.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(prod.getProperty("logging.level.root")).isEqualTo("INFO");
    }

    @Test
    void theSecurityPropertiesNoLongerHaveAForwardedForSwitch() throws Exception {
        assertThat(load("application.yml").getProperty("app.security.trust-forwarded-for")).isNull();
        assertThat(load("application.yml").getProperty("app.security.trusted-proxy-hops")).isNull();
        assertThat(load("application-prod.yml").getProperty("app.security.trust-forwarded-for")).isNull();
    }

    @Test
    void renderChecksALivenessEndpointThatDoesNotTouchTheDatabase() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("..");
        String render = java.nio.file.Files.readString(root.resolve("render.yaml"));
        String dockerfile = java.nio.file.Files.readString(root.resolve("backend/Dockerfile"));

        assertThat(render).contains("healthCheckPath: /api/v1/health").doesNotContain("healthCheckPath: /actuator");
        assertThat(dockerfile).contains("/api/v1/health").contains("-Dmaven.test.skip=true");
    }

    @Test
    void theDefaultInternalProxyPatternCoversPrivateRangesOnly() throws Exception {
        String value = (String) load("application-prod.yml").getProperty("server.tomcat.remoteip.internal-proxies");
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                value.substring("${INTERNAL_PROXIES:".length(), value.length() - 1));

        for (String internal : java.util.List.of("10.0.0.1", "10.201.3.4", "172.16.0.9", "172.31.255.1", "192.168.1.1",
                "100.64.0.1", "100.127.9.9", "127.0.0.1", "169.254.1.1", "::1", "fd12:3456::1", "fe80::1")) {
            assertThat(pattern.matcher(internal).matches()).as(internal).isTrue();
        }
        for (String external : java.util.List.of("203.0.113.5", "8.8.8.8", "172.32.0.1", "172.15.0.1", "100.128.0.1",
                "100.63.0.1", "11.0.0.1", "192.169.0.1", "2001:db8::1")) {
            assertThat(pattern.matcher(external).matches()).as(external).isFalse();
        }
    }

    @Test
    void theImageIsSizedForA512MbInstance() throws Exception {
        String dockerfile = java.nio.file.Files.readString(java.nio.file.Path.of("Dockerfile"));

        assertThat(dockerfile).contains("-XX:MaxRAMPercentage=65").contains("-XX:MaxMetaspaceSize=160m")
                .doesNotContain("MaxRAMPercentage=75");
        assertThat(load("application-prod.yml").getProperty("server.tomcat.threads.max")).isEqualTo(50);
    }

    @Test
    void theDemoProfileTurnsDemoModeOn() throws Exception {
        assertThat(load("application-demo.yml").getProperty("app.demo.enabled")).isEqualTo(true);
        assertThat(load("application.yml").getProperty("app.demo.enabled")).isEqualTo(false);
    }

    @Test
    void theTestSuiteDoesNotHoldIdleConnectionsOrUnboundedContexts() throws Exception {
        PropertySource<?> test = load("application-test.yml");
        java.util.Properties spring = new java.util.Properties();
        try (var in = new ClassPathResource("spring.properties").getInputStream()) {
            spring.load(in);
        }

        assertThat(test.getProperty("spring.datasource.hikari.minimum-idle")).isEqualTo(0);
        assertThat(test.getProperty("spring.datasource.hikari.idle-timeout")).isEqualTo(10000);
        assertThat(spring.getProperty("spring.test.context.cache.maxSize")).isEqualTo("12");
    }

    @Test
    void theDocsAgreeWithTheConfiguration() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("..");
        String deployment = java.nio.file.Files.readString(root.resolve("docs/DEPLOYMENT.md"));
        String architecture = java.nio.file.Files.readString(root.resolve("docs/ARCHITECTURE.md"));
        String render = java.nio.file.Files.readString(root.resolve("render.yaml"));

        // the INTERNAL_PROXIES example is pasted as is, so it must not double the backslashes
        assertThat(deployment).contains("paste exactly").doesNotContain("\\\\.");
        assertThat(architecture).doesNotContain("trust-forwarded-for").doesNotContain("trusted-proxy-hops")
                .doesNotContain("health check `/actuator/health`");
        assertThat(deployment).contains("DEMO_PASSWORD must be set when the demo profile is active")
                .doesNotContain("about 250 listings").contains("about 100 listings")
                .doesNotContain("every 10 minutes from a free monitor");
        assertThat(architecture).contains("/api/v1/health");
        assertThat(render).contains("healthCheckPath: /api/v1/health");
    }
}
