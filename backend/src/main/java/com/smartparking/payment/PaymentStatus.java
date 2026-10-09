package com.smartparking.payment;

import java.util.List;

public enum PaymentStatus {
    CREATED, CAPTURED, FAILED, REFUNDED, PARTIALLY_REFUNDED;

    /** Statuses of payments whose money actually moved: captured, and those since refunded in part or in full. */
    public static final List<PaymentStatus> MONEY_MOVED = List.of(CAPTURED, PARTIALLY_REFUNDED, REFUNDED);
}
