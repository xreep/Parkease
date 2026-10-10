package com.smartparking.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartparking.support.MutableClock;
import com.smartparking.user.UserRepository;
import com.smartparking.user.UserStatus;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UserStatusCacheTest {

    private final UserRepository users = mock(UserRepository.class);
    private final MutableClock clock = new MutableClock();
    private final UserStatusCache cache = new UserStatusCache(users, clock);

    @Test
    void cachesLookupsForAtMostSixtySeconds() {
        when(users.findStatusById(7L)).thenReturn(Optional.of(UserStatus.ACTIVE));
        assertThat(cache.statusOf(7L)).isEqualTo(UserStatus.ACTIVE);
        assertThat(cache.statusOf(7L)).isEqualTo(UserStatus.ACTIVE);
        verify(users, times(1)).findStatusById(7L);

        when(users.findStatusById(7L)).thenReturn(Optional.of(UserStatus.SUSPENDED));
        clock.advance(Duration.ofSeconds(30));
        assertThat(cache.statusOf(7L)).isEqualTo(UserStatus.ACTIVE); // still inside the window
        clock.advance(Duration.ofSeconds(31));
        assertThat(cache.statusOf(7L)).isEqualTo(UserStatus.SUSPENDED);
    }

    @Test
    void evictAndClearForceAFreshLookup() {
        when(users.findStatusById(7L)).thenReturn(Optional.of(UserStatus.ACTIVE));
        cache.statusOf(7L);
        when(users.findStatusById(7L)).thenReturn(Optional.of(UserStatus.SUSPENDED));
        cache.evict(7L);
        assertThat(cache.statusOf(7L)).isEqualTo(UserStatus.SUSPENDED);

        when(users.findStatusById(7L)).thenReturn(Optional.of(UserStatus.ACTIVE));
        cache.clear();
        assertThat(cache.statusOf(7L)).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void unknownUsersAreNotCachedAndCountAsActive() {
        when(users.findStatusById(9L)).thenReturn(Optional.empty());
        assertThat(cache.statusOf(9L)).isEqualTo(UserStatus.ACTIVE);
        when(users.findStatusById(9L)).thenReturn(Optional.of(UserStatus.SUSPENDED));
        assertThat(cache.statusOf(9L)).isEqualTo(UserStatus.SUSPENDED);
    }
}
