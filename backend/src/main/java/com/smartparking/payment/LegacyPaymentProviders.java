package com.smartparking.payment;

import java.util.List;

/**
 * Providers that no longer (or never) take new payments but still hold payments of this system, so refunds and
 * lookups of those payments reach them: the offline mock beside Razorpay on a hosted demo whose seeded history was
 * "paid" with it. Never used for a new checkout; see {@link PaymentProviders}.
 */
public record LegacyPaymentProviders(List<PaymentProvider> providers) {
}
