package com.smartparking.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Payment provider settings; Razorpay credentials are optional (absent ⇒ the mock provider is used). */
@ConfigurationProperties("app.payments")
public record PaymentProperties(Razorpay razorpay) {

    public record Razorpay(String keyId, String keySecret, String webhookSecret) {
    }

    /** Never null, so callers can read the credentials without a null check. */
    public Razorpay razorpayOrEmpty() {
        return razorpay != null ? razorpay : new Razorpay(null, null, null);
    }
}
