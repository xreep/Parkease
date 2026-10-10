package com.smartparking.admin.reports;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/** Arithmetic on aggregate rows shared by the KPI stats and the reports. */
final class ReportMath {

    private ReportMath() {
    }

    /** {@code part * 100 / whole} with one decimal (HALF_UP); 0.0 when there is nothing to divide by. */
    static BigDecimal percent(long part, long whole) {
        if (whole == 0) {
            return BigDecimal.ZERO.setScale(1);
        }
        return BigDecimal.valueOf(part * 100).divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }

    /** Money with two decimals; a missing value is zero. */
    static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    static <T> long sum(List<T> rows, ToLongFunction<T> field) {
        return rows.stream().mapToLong(field).sum();
    }

    /** Sum of the money values of the rows. */
    static <T> BigDecimal total(List<T> rows, Function<T, BigDecimal> field) {
        return money(rows.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}
