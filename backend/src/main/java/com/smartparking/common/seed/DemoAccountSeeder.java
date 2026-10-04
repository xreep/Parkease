package com.smartparking.common.seed;

import com.smartparking.owner.OwnerProfile;
import com.smartparking.owner.OwnerProfileRepository;
import com.smartparking.owner.VerificationStatus;
import com.smartparking.user.Role;
import com.smartparking.user.User;
import com.smartparking.user.UserRepository;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates admin/owner/driver demo logins on dev startup. Safe to run repeatedly. */
@Slf4j
@Component
@Profile("dev")
public class DemoAccountSeeder implements ApplicationRunner {

    public static final String ADMIN_EMAIL = "admin@smartpark.dev";
    public static final String OWNER_EMAIL = "owner@smartpark.dev";
    public static final String DRIVER_EMAIL = "driver@smartpark.dev";

    private final UserRepository users;
    private final OwnerProfileRepository ownerProfiles;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final String demoPassword;

    public DemoAccountSeeder(UserRepository users, OwnerProfileRepository ownerProfiles,
                             PasswordEncoder passwordEncoder, Clock clock,
                             @Value("${app.seed.demo-password}") String demoPassword) {
        this.users = users;
        this.ownerProfiles = ownerProfiles;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.demoPassword = demoPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seed();
        log.info("Demo accounts ready: {}, {}, {}", ADMIN_EMAIL, OWNER_EMAIL, DRIVER_EMAIL);
    }

    public void seed() {
        ensureUser(ADMIN_EMAIL, "Platform Admin", "9000000001", Role.ADMIN);
        User owner = ensureUser(OWNER_EMAIL, "Priya Sharma", "9000000002", Role.OWNER);
        ensureUser(DRIVER_EMAIL, "Rahul Verma", "9000000003", Role.DRIVER);

        OwnerProfile profile = ownerProfiles.findById(owner.getId())
                .orElseGet(() -> ownerProfiles.save(OwnerProfile.forUser(owner)));
        if (profile.getVerificationStatus() != VerificationStatus.VERIFIED) {
            profile.setVerificationStatus(VerificationStatus.VERIFIED);
            profile.setDocumentType("AADHAAR");
            profile.setPayoutUpi("priya.sharma@okaxis");
            profile.setPayoutAccountName("Priya Sharma");
            profile.setVerifiedAt(clock.instant());
        }
    }

    private User ensureUser(String email, String name, String phone, Role role) {
        return users.findByEmail(email).orElseGet(() -> {
            User user = new User();
            user.setEmail(email);
            user.setName(name);
            user.setPhone(phone);
            user.setRole(role);
            user.setEmailVerified(true);
            user.setPasswordHash(passwordEncoder.encode(demoPassword));
            return users.save(user);
        });
    }
}
