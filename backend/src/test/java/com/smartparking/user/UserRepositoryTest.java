package com.smartparking.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.owner.OwnerProfile;
import com.smartparking.owner.OwnerProfileRepository;
import com.smartparking.owner.VerificationStatus;
import com.smartparking.support.IntegrationTest;
import com.smartparking.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class UserRepositoryTest {

    @Autowired
    UserRepository users;

    @Autowired
    OwnerProfileRepository ownerProfiles;

    @Test
    void savesAndFindsUserByEmail() {
        User saved = users.save(TestUsers.newUser("asha@example.com", Role.DRIVER));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(users.existsByEmail("asha@example.com")).isTrue();
        assertThat(users.findByEmail("asha@example.com")).get()
                .extracting(User::getRole).isEqualTo(Role.DRIVER);
    }

    @Test
    void ownerProfileSharesTheUserId() {
        User owner = users.save(TestUsers.newUser("owner@example.com", Role.OWNER));

        OwnerProfile profile = ownerProfiles.save(OwnerProfile.forUser(owner));

        assertThat(profile.getUserId()).isEqualTo(owner.getId());
        assertThat(profile.getVerificationStatus()).isEqualTo(VerificationStatus.UNSUBMITTED);
    }
}
