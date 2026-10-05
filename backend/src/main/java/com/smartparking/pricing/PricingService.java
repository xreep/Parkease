package com.smartparking.pricing;

import com.smartparking.listing.ParkingListing;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** Turns unit prices and a time window into the cheapest valid quote (hourly / daily / monthly / mixed). */
@Service
public class PricingService {

    private static final long DAY_MINUTES = 1440;
    private static final long MONTH_MINUTES = 43200;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final PricingProperties properties;

    public PricingService(PricingProperties properties) {
        this.properties = properties;
    }

    public Quote quote(ParkingListing l, Instant start, Instant end) {
        return quote(l.getPricePerHour(), l.getPricePerDay(), l.getPricePerMonth(), start, end);
    }

    public Quote quote(BigDecimal pricePerHour, BigDecimal pricePerDay, BigDecimal pricePerMonth,
            Instant start, Instant end) {
        long minutes = Duration.between(start, end).toMinutes();
        if (minutes <= 0) {
            throw new IllegalArgumentException("end must be after start");
        }
        Option best = hourly(minutes, pricePerHour);
        if (pricePerDay != null) {
            best = cheaper(best, dayBased(minutes, pricePerHour, pricePerDay));
        }
        if (pricePerMonth != null) {
            best = cheaper(best, monthBased(minutes, pricePerHour, pricePerDay, pricePerMonth));
        }

        BigDecimal base = best.amount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal fee = base.multiply(properties.platformFeePercent()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal gst = fee.multiply(properties.gstPercent()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal total = base.add(fee).add(gst);
        return new Quote(best.mode, minutes, base, fee, gst, total, best.breakdown);
    }

    /** Strictly cheaper wins; ties keep the earlier (simpler) option. */
    private static Option cheaper(Option current, Option candidate) {
        return candidate.amount.compareTo(current.amount) < 0 ? candidate : current;
    }

    private static Option hourly(long minutes, BigDecimal pricePerHour) {
        long hours = ceilDiv(minutes, 60);
        return new Option(pricePerHour.multiply(BigDecimal.valueOf(hours)), PricingMode.HOURLY, plural(hours, "hour"));
    }

    private static Option dayBased(long minutes, BigDecimal pricePerHour, BigDecimal pricePerDay) {
        long days = minutes / DAY_MINUTES;
        long rem = minutes % DAY_MINUTES;
        BigDecimal amount = pricePerDay.multiply(BigDecimal.valueOf(days));
        if (rem == 0) {
            return new Option(amount, PricingMode.DAILY, plural(days, "day"));
        }
        Option remHourly = hourly(rem, pricePerHour);
        if (remHourly.amount.compareTo(pricePerDay) >= 0) {
            return new Option(amount.add(pricePerDay), PricingMode.DAILY, plural(days + 1, "day"));
        }
        String breakdown = (days > 0 ? plural(days, "day") + " + " : "") + remHourly.breakdown;
        return new Option(amount.add(remHourly.amount), PricingMode.MIXED, breakdown);
    }

    private static Option monthBased(long minutes, BigDecimal pricePerHour, BigDecimal pricePerDay,
            BigDecimal pricePerMonth) {
        long months = minutes / MONTH_MINUTES;
        long rem = minutes % MONTH_MINUTES;
        BigDecimal amount = pricePerMonth.multiply(BigDecimal.valueOf(months));
        if (rem == 0) {
            return new Option(amount, PricingMode.MONTHLY, plural(months, "month"));
        }
        Option remBest = hourly(rem, pricePerHour);
        if (pricePerDay != null) {
            remBest = cheaper(remBest, dayBased(rem, pricePerHour, pricePerDay));
        }
        if (remBest.amount.compareTo(pricePerMonth) >= 0) {
            return new Option(amount.add(pricePerMonth), PricingMode.MONTHLY, plural(months + 1, "month"));
        }
        String breakdown = (months > 0 ? plural(months, "month") + " + " : "") + remBest.breakdown;
        return new Option(amount.add(remBest.amount), PricingMode.MIXED, breakdown);
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }

    private static String plural(long n, String unit) {
        return n + " " + unit + (n == 1 ? "" : "s");
    }

    private record Option(BigDecimal amount, PricingMode mode, String breakdown) {
    }
}
