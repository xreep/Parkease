package com.smartparking.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Like {@link IntegrationTest} but without the per-test rollback: data really commits. Needed when the code under test
 * opens {@code REQUIRES_NEW} transactions (slot allocation), which cannot see a rolled-back test's uncommitted rows.
 * Tests must call {@link DatabaseCleaner#clean} before and after each test.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestEmailConfig.class)
public @interface CommittedIntegrationTest {
}
