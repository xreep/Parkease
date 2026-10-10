package com.smartparking.common.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.context.annotation.Profile;

/** The demo data is opt-in: dev and the demo profile run the seeders, nothing else does. */
class DemoProfileConfigTest {

    @Test
    void everySeederRunsUnderDevAndDemoOnly() {
        for (Class<?> seeder : List.of(DemoAccountSeeder.class, DemoListingSeeder.class, DemoActivitySeeder.class)) {
            Profile profile = AnnotationUtils.findAnnotation(seeder, Profile.class);
            assertThat(profile).as(seeder.getSimpleName()).isNotNull();
            assertThat(Set.of(profile.value())).as(seeder.getSimpleName()).containsExactlyInAnyOrder("dev", "demo");
        }
    }

    @Test
    void theDemoProfileTakesItsPasswordFromTheEnvironmentWithoutADefault() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application-demo", new ClassPathResource("application-demo.yml"));
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).getProperty("app.seed.demo-password")).isEqualTo("${DEMO_PASSWORD:}");
    }
}
