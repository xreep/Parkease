package com.smartparking.common.seed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DemoModeTest {

    @Test
    void isOffUnlessEnabled() {
        assertThat(new DemoMode(false).enabled()).isFalse();
        assertThat(new DemoMode(true).enabled()).isTrue();
    }

    @Test
    void theShowcaseLoginsAreTheSeededParkeaseDevAddresses() {
        DemoMode demo = new DemoMode(true);

        for (String email : new String[] {"admin@parkease.dev", "owner@parkease.dev", "driver@parkease.dev",
                "owner.north@parkease.dev", "Owner.Pending@ParkEase.dev"}) {
            assertThat(demo.isShowcaseAccount(email)).as(email).isTrue();
        }
        for (String email : new String[] {"someone@example.com", "x@gmail.com", "admin@parkease.dev.evil.com", null, ""}) {
            assertThat(demo.isShowcaseAccount(email)).as(String.valueOf(email)).isFalse();
        }
    }

    @Test
    void nothingIsProtectedOutsideDemoMode() {
        assertThat(new DemoMode(false).isLockedAccount("admin@parkease.dev")).isFalse();
        assertThat(new DemoMode(true).isLockedAccount("admin@parkease.dev")).isTrue();
        assertThat(new DemoMode(true).isLockedAccount("a@example.com")).isFalse();
    }
}
