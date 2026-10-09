package com.smartparking.payment;

import com.smartparking.common.error.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The payment provider refused a call or could not be reached. The message shown to clients stays generic;
 * {@link #getProviderMessage()} is the provider's own description (for our records, never for responses).
 */
public class PaymentProviderException extends ApiException {

    private final String providerMessage;

    public PaymentProviderException(String providerMessage) {
        super(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER_ERROR", "Payment provider is unavailable. Please try again.");
        this.providerMessage = providerMessage;
    }

    public String getProviderMessage() {
        return providerMessage;
    }

    /** The provider's description when the exception carries one, otherwise the exception's own message. */
    public static String describe(ApiException e) {
        return e instanceof PaymentProviderException p && p.providerMessage != null && !p.providerMessage.isBlank()
                ? p.providerMessage : e.getMessage();
    }
}
