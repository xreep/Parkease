package com.smartparking.payment;

/** Why the driver is told about a refund by email. */
public enum RefundNotice {
    /** The payment arrived but the slot could not be held (hold lapsed and slot taken, or start time passed). */
    SLOT_LOST,
    /** The payment arrived for a booking that was already cancelled, declined or otherwise closed. */
    BOOKING_CLOSED,
    /**
     * The refund of a cancellation (by the driver or the owner). The cancellation's own message already covers the
     * first attempt, so nothing is sent for it; a refund that failed is announced when a retry (or the provider) gets
     * it through, once.
     */
    CANCELLATION,
    /**
     * The refund of a dispute resolution. The resolution's own message covers the first attempt; a refund that
     * failed is announced when a retry gets it through, once.
     */
    DISPUTE
}
