package com.smartparking.common.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.owner.OwnerProfileRepository;
import com.smartparking.owner.VerificationStatus;
import com.smartparking.support.IntegrationTest;
import com.smartparking.user.Role;
import com.smartparking.user.User;
import com.smartparking.user.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

@IntegrationTest
class DemoAccountSeederTest {

    @Autowired
    UserRepository users;

    @Autowired
    OwnerProfileRepository ownerProfiles;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    Clock clock;

    @Autowired
    EntityManager em;

    @Test
    void seedsThreeVerifiedDemoAccountsIdempotently() {
        DemoAccountSeeder seeder = new DemoAccountSeeder(users, ownerProfiles, passwordEncoder, clock, "Demo@1234");

        seeder.seed();
        seeder.seed();
        em.flush();
        em.clear();

        User admin = users.findByEmail(DemoAccountSeeder.ADMIN_EMAIL).orElseThrow();
        User owner = users.findByEmail(DemoAccountSeeder.OWNER_EMAIL).orElseThrow();
        User driver = users.findByEmail(DemoAccountSeeder.DRIVER_EMAIL).orElseThrow();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(owner.getRole()).isEqualTo(Role.OWNER);
        assertThat(driver.getRole()).isEqualTo(Role.DRIVER);
        assertThat(driver.isEmailVerified()).isTrue();
        assertThat(passwordEncoder.matches("Demo@1234", driver.getPasswordHash())).isTrue();
        assertThat(ownerProfiles.findById(owner.getId())).get()
                .extracting(p -> p.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(users.findAll().stream().filter(u -> u.getEmail().endsWith("@smartpark.dev"))).hasSize(3);
    }
}
