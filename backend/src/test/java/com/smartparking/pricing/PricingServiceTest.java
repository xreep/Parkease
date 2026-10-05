package com.smartparking.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PricingServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-06T04:30:00Z");

    private final PricingService service =
            new PricingService(new PricingProperties(new BigDecimal("10"), new BigDecimal("18")));

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private Quote quote(Duration d) {
        return service.quote(bd("40"), bd("250"), bd("4500"), T0, T0.plus(d));
    }

    @Test
    void exactHours() {
        Quote q = quote(Duration.ofHours(3));
        assertThat(q.baseAmount()).isEqualByComparingTo("120.00");
        assertThat(q.pricingMode()).isEqualTo(PricingMode.HOURLY);
        assertThat(q.breakdown()).isEqualTo("3 hours");
        assertThat(q.durationMinutes()).isEqualTo(180);
        assertThat(q.platformFee()).isEqualByComparingTo("12.00");
        assertThat(q.gstAmount()).isEqualByComparingTo("2.16");
        assertThat(q.totalAmount()).isEqualByComparingTo("134.16");
    }

    @Test
    void partialHourRoundsUp() {
        Quote q = quote(Duration.ofMinutes(75));
        assertThat(q.baseAmount()).isEqualByComparingTo("80.00");
        assertThat(q.pricingMode()).isEqualTo(PricingMode.HOURLY);
        assertThat(q.breakdown()).isEqualTo("2 hours");
    }

    @Test
    void dailyCapBeatsHourly() {
        Quote q = quote(Duration.ofHours(8));
        assertThat(q.baseAmount()).isEqualByComparingTo("250.00");
        assertThat(q.pricingMode()).isEqualTo(PricingMode.DAILY);
        assertThat(q.breakdown()).isEqualTo("1 day");
    }

    @Test
    void mixedDaysAndHours() {
        Quote q = quote(Duration.ofDays(2).plusHours(3));
        assertThat(q.baseAmount()).isEqualByComparingTo("620.00");
        assertThat(q.pricingMode()).isEqualTo(PricingMode.MIXED);
        assertThat(q.breakdown()).isEqualTo("2 days + 3 hours");
    }

    @Test
    void remainderCappedAtDay() {
        Quote q = quote(Duration.ofDays(2).plusHours(10));
        assertThat(q.baseAmount()).isEqualByComparingTo("750.00");
        assertThat(q.pricingMode()).isEqualTo(PricingMode.DAILY);
        assertThat(q.breakdown()).isEqualTo("3 days");
    }

    @Test
    void monthlyWins() {
        Quote q = quote(Duration.ofDays(30));
        assertThat(q.baseAmount()).isEqualByComparingTo("4500.00");
        assertThat(q.pricingMode()).isEqualTo(PricingMode.MONTHLY);
        assertThat(q.breakdown()).isEqualTo("1 month");
    }

    @Test
    void monthPlusDays() {
        Quote q = quote(Duration.ofDays(34));
        assertThat(q.baseAmount()).isEqualByComparingTo("5500.00");
        assertThat(q.pricingMode()).isEqualTo(PricingMode.MIXED);
        assertThat(q.breakdown()).isEqualTo("1 month + 4 days");
    }

    @Test
    void monthPlusDaysAndHours() {
        Quote q = quote(Duration.ofDays(32).plusHours(5));
        assertThat(q.baseAmount()).isEqualByComparingTo("5200.00");
        assertThat(q.pricingMode()).isEqualTo(PricingMode.MIXED);
        assertThat(q.breakdown()).isEqualTo("1 month + 2 days + 5 hours");
    }

    @Test
    void noDailyPriceUsesHourly() {
        Quote q = service.quote(bd("40"), null, null, T0, T0.plus(Duration.ofHours(26)));
        assertThat(q.baseAmount()).isEqualByComparingTo("1040.00");
        assertThat(q.pricingMode()).isEqualTo(PricingMode.HOURLY);
        assertThat(q.breakdown()).isEqualTo("26 hours");
    }

    @Test
    void feesRoundHalfUp() {
        Quote q = service.quote(bd("33.33"), null, null, T0, T0.plus(Duration.ofHours(1)));
        assertThat(q.baseAmount()).isEqualByComparingTo("33.33");
        assertThat(q.platformFee()).isEqualByComparingTo("3.33");
        assertThat(q.gstAmount()).isEqualByComparingTo("0.60");
        assertThat(q.totalAmount()).isEqualByComparingTo("37.26");
    }

    @Test
    void singularLabels() {
        assertThat(quote(Duration.ofHours(1)).breakdown()).isEqualTo("1 hour");
        assertThat(quote(Duration.ofHours(24)).breakdown()).isEqualTo("1 day");
    }
}
