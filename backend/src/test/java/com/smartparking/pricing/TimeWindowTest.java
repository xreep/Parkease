package com.smartparking.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartparking.common.error.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TimeWindowTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-06T04:37:00Z"), ZoneOffset.UTC);

    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    private void assertInvalid(Instant s, Instant e, String message) {
        assertThatThrownBy(() -> TimeWindow.of(s, e, clock))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INVALID_TIME_RANGE");
                    assertThat(ex.getMessage()).contains(message);
                });
    }

    @Test
    void validWindow() {
        TimeWindow w = TimeWindow.of(at("2026-10-06T05:00:00Z"), at("2026-10-06T07:00:00Z"), clock);
        assertThat(w.minutes()).isEqualTo(120);
    }

    @Test
    void startAtCurrentQuarterIsAllowed() {
        TimeWindow w = TimeWindow.of(at("2026-10-06T04:30:00Z"), at("2026-10-06T06:30:00Z"), clock);
        assertThat(w.start()).isEqualTo(at("2026-10-06T04:30:00Z"));
    }

    @Test
    void startInThePastRejected() {
        assertInvalid(at("2026-10-06T04:15:00Z"), at("2026-10-06T06:15:00Z"), "Start time can't be in the past");
    }

    @Test
    void notOnQuarterRejected() {
        assertInvalid(at("2026-10-06T05:10:00Z"), at("2026-10-06T07:00:00Z"), "Times must be on 15-minute steps");
        assertInvalid(at("2026-10-06T05:00:00Z"), at("2026-10-06T07:05:00Z"), "Times must be on 15-minute steps");
        assertInvalid(at("2026-10-06T05:00:30Z"), at("2026-10-06T07:00:00Z"), "Times must be on 15-minute steps");
        assertInvalid(at("2026-10-06T05:00:00.5Z"), at("2026-10-06T07:00:00Z"), "Times must be on 15-minute steps");
    }

    @Test
    void endNotAfterStartRejected() {
        assertInvalid(at("2026-10-06T05:00:00Z"), at("2026-10-06T05:00:00Z"), "End time must be after start time");
        assertInvalid(at("2026-10-06T05:00:00Z"), at("2026-10-06T04:45:00Z"), "End time must be after start time");
    }

    @Test
    void tooShortRejected() {
        assertInvalid(at("2026-10-06T05:00:00Z"), at("2026-10-06T05:45:00Z"), "at least 1 hour");
    }

    @Test
    void tooLongRejected() {
        Instant s = at("2026-10-06T05:00:00Z");
        assertInvalid(s, s.plus(Duration.ofDays(91)), "at most 90 days");
        assertThat(TimeWindow.of(s, s.plus(Duration.ofDays(90)), clock).minutes()).isEqualTo(90 * 1440);
    }

    @Test
    void optionalHandling() {
        assertThat(TimeWindow.optional(null, null, clock)).isEmpty();
        assertThat(TimeWindow.optional(at("2026-10-06T05:00:00Z"), at("2026-10-06T07:00:00Z"), clock)).isPresent();
        for (Instant[] pair : new Instant[][] {
            {at("2026-10-06T05:00:00Z"), null}, {null, at("2026-10-06T07:00:00Z")}}) {
            assertThatThrownBy(() -> TimeWindow.optional(pair[0], pair[1], clock))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo("INVALID_TIME_RANGE");
                        assertThat(ex.getMessage()).contains("Choose both a start and an end time");
                    });
        }
    }

    @Test
    void overlapsIsHalfOpen() {
        TimeWindow w = TimeWindow.of(at("2026-10-06T05:00:00Z"), at("2026-10-06T07:00:00Z"), clock);
        assertThat(w.overlaps(at("2026-10-06T06:00:00Z"), at("2026-10-06T08:00:00Z"))).isTrue();
        assertThat(w.overlaps(at("2026-10-06T04:00:00Z"), at("2026-10-06T05:00:00Z"))).isFalse();
        assertThat(w.overlaps(at("2026-10-06T07:00:00Z"), at("2026-10-06T08:00:00Z"))).isFalse();
        assertThat(w.overlaps(at("2026-10-06T05:30:00Z"), at("2026-10-06T06:00:00Z"))).isTrue();
        assertThat(w.overlaps(at("2026-10-06T04:00:00Z"), at("2026-10-06T09:00:00Z"))).isTrue();
    }
}
