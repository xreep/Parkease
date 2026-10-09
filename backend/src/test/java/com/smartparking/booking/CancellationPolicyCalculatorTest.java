package com.smartparking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.listing.CancellationPolicy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CancellationPolicyCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-10-10T06:00:00Z");
    private static final BigDecimal BASE = new BigDecimal("60.00");

    private static CancellationPolicyCalculator.Result at(CancellationPolicy policy, long minutesBefore,
                                                          BigDecimal base) {
        return CancellationPolicyCalculator.preview(policy, NOW.plus(Duration.ofMinutes(minutesBefore)), NOW, base);
    }

    @ParameterizedTest(name = "{0} at {1} min before start refunds {2}%")
    @CsvSource({
            "FLEXIBLE, 59, 50",
            "FLEXIBLE, 60, 100",
            "FLEXIBLE, 0, 50",
            "FLEXIBLE, 100000, 100",
            "MODERATE, 1439, 50",
            "MODERATE, 1440, 100",
            "MODERATE, 120, 50",
            "MODERATE, 119, 0",
            "MODERATE, 1, 0",
            "STRICT, 2879, 0",
            "STRICT, 2880, 50",
            "STRICT, 100000, 50",
            "STRICT, 60, 0",
    })
    void thresholdsUseExactMinutes(CancellationPolicy policy, long minutes, int percent) {
        assertThat(at(policy, minutes, BASE).percent()).isEqualTo(percent);
    }

    @ParameterizedTest
    @CsvSource({"FLEXIBLE", "MODERATE", "STRICT"})
    void aSecondShortOfTheThresholdFallsIntoTheLowerTier(CancellationPolicy policy) {
        long threshold = switch (policy) {
            case FLEXIBLE -> 60;
            case MODERATE -> 1440;
            case STRICT -> 2880;
        };
        Instant start = NOW.plus(Duration.ofMinutes(threshold)).minusSeconds(1);
        int below = CancellationPolicyCalculator.preview(policy, start, NOW, BASE).percent();
        int at = CancellationPolicyCalculator.preview(policy, start.plusSeconds(1), NOW, BASE).percent();
        assertThat(below).isLessThan(at);
    }

    @ParameterizedTest
    @CsvSource({
            "60.00, 100, 60.00",
            "60.00, 50, 30.00",
            "33.33, 50, 16.67",
            "33.33, 100, 33.33",
            "0.01, 50, 0.01",
            "0.03, 50, 0.02",
            "100.00, 0, 0.00",
    })
    void refundIsTheBasePercentRoundedHalfUpToTwoDecimals(String base, int percent, String expected) {
        // MODERATE: >=1440 -> 100, >=120 -> 50, else 0
        long minutes = percent == 100 ? 1440 : percent == 50 ? 120 : 0;
        CancellationPolicyCalculator.Result result = at(CancellationPolicy.MODERATE, minutes, new BigDecimal(base));
        assertThat(result.percent()).isEqualTo(percent);
        assertThat(result.refundAmount()).isEqualByComparingTo(expected);
        assertThat(result.refundAmount().scale()).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({"180, 3.0", "90, 1.5", "100, 1.6", "125, 2.0", "0, 0.0", "1440, 24.0", "1439, 23.9", "59, 0.9", "119, 1.9"})
    void hoursBeforeStartIsCutToOneDecimalSoItNeverContradictsThePercent(long minutes, String hours) {
        assertThat(at(CancellationPolicy.FLEXIBLE, minutes, BASE).hoursBeforeStart()).isEqualByComparingTo(hours);
        assertThat(at(CancellationPolicy.FLEXIBLE, minutes, BASE).hoursBeforeStart().scale()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"-30, 0.0", "0, 0.0"})
    void hoursBeforeStartIsNeverNegative(long minutes, String hours) {
        Instant start = NOW.plus(Duration.ofMinutes(minutes));
        assertThat(CancellationPolicyCalculator.hoursBefore(start, NOW)).isEqualByComparingTo(hours);
    }
}
