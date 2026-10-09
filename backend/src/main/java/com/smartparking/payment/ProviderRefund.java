package com.smartparking.payment;

import java.util.Map;

/**
 * A refund as the provider reports it. {@code amountPaise}, {@code receipt} and {@code notes} are what we sent when we
 * created it (null/empty when the provider does not echo them, or the refund was made in the provider's dashboard).
 */
public record ProviderRefund(String refundId, RefundStatus status, Long amountPaise, String receipt,
                             Map<String, String> notes) {

    public ProviderRefund(String refundId, RefundStatus status) {
        this(refundId, status, null, null, Map.of());
    }
}
