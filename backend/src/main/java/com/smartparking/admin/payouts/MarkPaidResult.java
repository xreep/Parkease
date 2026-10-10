package com.smartparking.admin.payouts;

import java.math.BigDecimal;

public record MarkPaidResult(int paidCount, BigDecimal paidAmount) {
}
