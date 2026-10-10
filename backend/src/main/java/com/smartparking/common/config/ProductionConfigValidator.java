package com.smartparking.common.config;

import com.smartparking.common.security.JwtProperties;
import com.smartparking.email.AppMailProperties;
import com.smartparking.storage.StorageProperties;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Refuses to start the {@code prod} profile with settings that would make a public deployment unsafe, reporting every
 * problem at once. Payment keys are checked separately by {@code PaymentConfig}. Mail and Cloudinary are optional but
 * logged loudly when missing. It runs as a {@code BeanFactoryPostProcessor} (see {@link ProductionConfigCheck}), i.e.
 * before any bean that would otherwise trip over the same problem with a less helpful message.
 */
public class ProductionConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(ProductionConfigValidator.class);

    /** Secrets committed to application-dev.yml and application-test.yml: public, so never acceptable in prod. */
    static final Set<String> KNOWN_SECRETS = Set.of(
            "ZGV2LW9ubHktand0LXNlY3JldC1zbWFydC1wYXJraW5nLXBsYXRmb3JtLTIwMjYh",
            "dGVzdC1vbmx5LWp3dC1zZWNyZXQtc21hcnQtcGFya2luZy1wbGF0Zm9ybS0yMDI2");

    private final JwtProperties jwt;
    private final AppProperties app;
    private final StorageProperties storage;
    private final AppMailProperties mail;
    private final Environment environment;

    public ProductionConfigValidator(JwtProperties jwt, AppProperties app, StorageProperties storage,
                                     AppMailProperties mail, Environment environment) {
        this.jwt = jwt;
        this.app = app;
        this.storage = storage;
        this.mail = mail;
        this.environment = environment;
    }

    /** Reads the settings straight from the environment, so it can run before any bean exists. */
    static ProductionConfigValidator of(Environment environment) {
        Binder binder = Binder.get(environment);
        return new ProductionConfigValidator(
                binder.bind("app.jwt", JwtProperties.class).orElse(new JwtProperties(null, null, null)),
                binder.bind("app", AppProperties.class).orElse(new AppProperties(null, null, 0, 0)),
                binder.bind("app.storage", StorageProperties.class).orElse(new StorageProperties(null, null, null)),
                binder.bind("app.mail", AppMailProperties.class).orElse(new AppMailProperties(null, 0, null, null, null)),
                environment);
    }

    public void validate() {
        warnings().forEach(log::warn);
        List<String> problems = problems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Unsafe production configuration:\n - " + String.join("\n - ", problems));
        }
    }

    List<String> problems() {
        List<String> problems = new ArrayList<>();
        jwtSecretProblem().ifPresent(problems::add);

        String dbUrl = databaseUrl();
        if (isBlank(dbUrl)) {
            problems.add("DB_URL must be set to the PostgreSQL JDBC URL (jdbc:postgresql://host/db?sslmode=require)");
        } else if (!dbUrl.startsWith("jdbc:postgresql://")) {
            problems.add("DB_URL must be a PostgreSQL JDBC URL starting with jdbc:postgresql://");
        }

        if (isBlank(app.frontendUrl())) {
            problems.add("FRONTEND_URL must be set to the https URL of the frontend");
        } else if (!app.frontendUrl().startsWith("https://")) {
            problems.add("FRONTEND_URL must be an https URL (it is used in e-mailed links)");
        }

        List<String> origins = app.corsAllowedOrigins();
        if (origins == null || origins.stream().allMatch(ProductionConfigValidator::isBlank)) {
            problems.add("CORS_ALLOWED_ORIGINS (or FRONTEND_URL) must name the frontend origin");
        } else if (origins.stream().anyMatch(o -> !o.startsWith("https://") || o.contains("*"))) {
            problems.add("CORS_ALLOWED_ORIGINS must list exact https origins (no http, no wildcards)");
        }
        return problems;
    }

    private Optional<String> jwtSecretProblem() {
        String secret = jwt.secret();
        if (isBlank(secret)) {
            return Optional.of("JWT_SECRET must be set (generate one with: openssl rand -base64 48)");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException e) {
            return Optional.of("JWT_SECRET must be base64 (generate one with: openssl rand -base64 48)");
        }
        if (decoded.length < 32) {
            return Optional.of("JWT_SECRET must decode to at least 32 bytes (generate one with: "
                    + "openssl rand -base64 48)");
        }
        if (KNOWN_SECRETS.contains(secret.trim())) {
            return Optional.of("JWT_SECRET is a development secret that is published in the repository; "
                    + "generate a new one (openssl rand -base64 48)");
        }
        return Optional.empty();
    }

    List<String> warnings() {
        List<String> warnings = new ArrayList<>();
        if (!StringUtils.hasText(mail.host())) {
            warnings.add("MAIL_HOST is not set: e-mails (verification, reset, booking notices) are only logged, "
                    + "not delivered");
        }
        if (!StringUtils.hasText(storage.cloudinaryUrl())) {
            warnings.add("CLOUDINARY_URL is not set: uploads go to local disk, which is ephemeral on most hosts "
                    + "(Render redeploys and restarts lose every photo and owner document)");
        }
        String dbUrl = databaseUrl();
        if (!isBlank(dbUrl) && !dbUrl.contains("sslmode=require") && !dbUrl.contains("//localhost")
                && !dbUrl.contains("//127.0.0.1")) {
            warnings.add("DB_URL does not contain sslmode=require: the connection to a hosted database "
                    + "(e.g. Neon) should be encrypted");
        }
        return warnings;
    }

    private String databaseUrl() {
        try {
            return environment.getProperty("spring.datasource.url");
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            return null;
        }
    }

    /** Missing, empty, or a placeholder such as ${JWT_SECRET} that nothing filled in. */
    private static boolean isBlank(String value) {
        return !StringUtils.hasText(value) || value.trim().startsWith("${");
    }
}
