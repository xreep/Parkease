package com.smartparking.payment;

/** Why the driver is told about a refund by email. */
public enum RefundNotice {
    /** The payment arrived but the slot could not be held (hold lapsed and slot taken, or start time passed). */
    SLOT_LOST,
    /** The payment arrived for a booking that was already cancelled, declined or otherwise closed. */
    BOOKING_CLOSED
}
