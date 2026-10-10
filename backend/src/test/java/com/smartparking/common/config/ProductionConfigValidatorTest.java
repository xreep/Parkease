package com.smartparking.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartparking.common.security.JwtProperties;
import com.smartparking.email.AppMailProperties;
import com.smartparking.storage.StorageProperties;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionConfigValidatorTest {

    /** The secret committed in application-dev.yml. */
    static final String DEV_SECRET = "ZGV2LW9ubHktand0LXNlY3JldC1zbWFydC1wYXJraW5nLXBsYXRmb3JtLTIwMjYh";
    /** The secret committed in application-test.yml. */
    static final String TEST_SECRET = "dGVzdC1vbmx5LWp3dC1zZWNyZXQtc21hcnQtcGFya2luZy1wbGF0Zm9ybS0yMDI2";

    static String strongSecret() {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** Builder-style fixture: starts valid, each test breaks one thing. */
    static class Config {
        String jwtSecret = strongSecret();
        String dbUrl = "jdbc:postgresql://ep-x.neon.tech/neondb?sslmode=require";
        String frontendUrl = "https://parkease.vercel.app";
        List<String> cors = List.of("https://parkease.vercel.app");
        String mailHost = "smtp-relay.brevo.com";
        String cloudinaryUrl = "cloudinary://key:secret@cloud";

        Config jwt(String v) { jwtSecret = v; return this; }
        Config db(String v) { dbUrl = v; return this; }
        Config frontend(String v) { frontendUrl = v; return this; }
        Config cors(String... v) { cors = v == null ? null : List.of(v); return this; }
        Config mail(String v) { mailHost = v; return this; }
        Config cloudinary(String v) { cloudinaryUrl = v; return this; }

        ProductionConfigValidator build() {
            MockEnvironment env = new MockEnvironment();
            if (dbUrl != null) {
                env.setProperty("spring.datasource.url", dbUrl);
            }
            return new ProductionConfigValidator(
                    new JwtProperties(jwtSecret, Duration.ofMinutes(15), Duration.ofDays(7)),
                    new AppProperties(frontendUrl, cors, 12, 10),
                    new StorageProperties("uploads", "https://api.example.com", cloudinaryUrl),
                    new AppMailProperties(mailHost, 587, "u", "p", "ParkEase <no-reply@example.com>"),
                    env);
        }
    }

    private static void assertRefuses(Config config, String... messageParts) {
        assertThatThrownBy(() -> config.build().validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContainingAll(messageParts);
    }

    @Test
    void aCompleteProductionConfigurationPasses() {
        assertThatCode(() -> new Config().build().validate()).doesNotThrowAnyException();
    }

    @Test
    void rejectsAMissingJwtSecret() {
        assertRefuses(new Config().jwt(null), "JWT_SECRET");
        assertRefuses(new Config().jwt(""), "JWT_SECRET");
        assertRefuses(new Config().jwt("   "), "JWT_SECRET");
    }

    @Test
    void rejectsAJwtSecretThatIsNotBase64() {
        assertRefuses(new Config().jwt("not base64 !!!"), "JWT_SECRET", "base64");
    }

    @Test
    void rejectsAJwtSecretShorterThan32Bytes() {
        String thirtyOneBytes = Base64.getEncoder().encodeToString(new byte[31]);
        assertRefuses(new Config().jwt(thirtyOneBytes), "JWT_SECRET", "32 bytes");
        assertRefuses(new Config().jwt("c2VjcmV0"), "JWT_SECRET", "32 bytes");
        assertThatCode(() -> new Config().jwt(Base64.getEncoder().encodeToString(randomBytes(32))).build().validate())
                .doesNotThrowAnyException();
    }

    private static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    @Test
    void rejectsTheSecretsCommittedInTheRepository() {
        assertRefuses(new Config().jwt(DEV_SECRET), "JWT_SECRET", "development");
        assertRefuses(new Config().jwt(TEST_SECRET), "JWT_SECRET", "development");
    }

    @Test
    void rejectsAMissingOrNonJdbcDatabaseUrl() {
        assertRefuses(new Config().db(null), "DB_URL");
        assertRefuses(new Config().db(" "), "DB_URL");
        assertRefuses(new Config().db("postgres://host/db"), "DB_URL", "jdbc:postgresql://");
    }

    @Test
    void rejectsAMissingOrInsecureFrontendUrl() {
        assertRefuses(new Config().frontend(null), "FRONTEND_URL");
        assertRefuses(new Config().frontend(""), "FRONTEND_URL");
        assertRefuses(new Config().frontend("http://parkease.vercel.app"), "FRONTEND_URL", "https");
        assertRefuses(new Config().frontend("http://localhost:5173"), "FRONTEND_URL", "https");
    }

    @Test
    void rejectsMissingOrInsecureCorsOrigins() {
        assertRefuses(new Config().cors((String[]) null), "CORS_ALLOWED_ORIGINS");
        assertRefuses(new Config().cors(), "CORS_ALLOWED_ORIGINS");
        assertRefuses(new Config().cors("http://localhost:5173"), "CORS_ALLOWED_ORIGINS", "https");
        assertRefuses(new Config().cors("https://parkease.vercel.app", "http://evil.example"),
                "CORS_ALLOWED_ORIGINS", "https");
        assertRefuses(new Config().cors("*"), "CORS_ALLOWED_ORIGINS");
        assertRefuses(new Config().cors("https://*.vercel.app"), "CORS_ALLOWED_ORIGINS");
    }

    @Test
    void reportsEveryProblemAtOnce() {
        assertRefuses(new Config().jwt(DEV_SECRET).db(null).frontend("http://x"),
                "JWT_SECRET", "DB_URL", "FRONTEND_URL");
    }

    @Test
    void neverEchoesTheSecretInTheFailureMessage() {
        assertThatThrownBy(() -> new Config().jwt(DEV_SECRET).build().validate())
                .hasMessageNotContaining(DEV_SECRET);
        assertThatThrownBy(() -> new Config().jwt("c2VjcmV0").build().validate())
                .hasMessageNotContaining("c2VjcmV0");
    }

    @Test
    void mailAndCloudinaryAreOptionalWithWarnings() {
        ProductionConfigValidator validator = new Config().mail("").cloudinary("").build();

        assertThatCode(validator::validate).doesNotThrowAnyException();
        assertThat(validator.warnings()).hasSize(2);
        assertThat(validator.warnings()).anyMatch(w -> w.contains("MAIL_HOST"));
        assertThat(validator.warnings()).anyMatch(w -> w.contains("CLOUDINARY_URL") && w.contains("ephemeral"));
        assertThat(new Config().build().warnings()).isEmpty();
    }

    @Test
    void warnsWhenTheDatabaseUrlDoesNotRequireSsl() {
        assertThat(new Config().db("jdbc:postgresql://ep-x.neon.tech/neondb").build().warnings())
                .anyMatch(w -> w.contains("sslmode=require"));
        assertThat(new Config().db("jdbc:postgresql://localhost:5432/db").build().warnings())
                .noneMatch(w -> w.contains("sslmode"));
    }

    @Test
    void everySecretCommittedInTheDevAndTestConfigIsOnTheRefusedList() throws Exception {
        String dev = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/application-dev.yml"));
        String test = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/application-test.yml"));
        java.util.regex.Matcher devSecret = java.util.regex.Pattern
                .compile("secret: \\$\\{JWT_SECRET:([^}]+)}").matcher(dev);
        java.util.regex.Matcher testSecret = java.util.regex.Pattern.compile("secret: (\\S+)").matcher(test);

        assertThat(devSecret.find()).as("dev secret default").isTrue();
        assertThat(testSecret.find()).as("test secret").isTrue();
        assertThat(ProductionConfigValidator.KNOWN_SECRETS)
                .contains(devSecret.group(1).trim(), testSecret.group(1).trim());
    }
}
