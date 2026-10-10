package com.smartparking.payment;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Which provider handles which payment. New checkouts use the {@linkplain #primary() primary} provider (the one
 * configured by {@link PaymentConfig}); anything done to an existing payment (refunds, lookups, signature checks)
 * goes to the provider recorded on the payment row, which may be a {@linkplain LegacyPaymentProviders legacy} one.
 * A legacy provider of the primary's own type never replaces it.
 */
@Component
public class PaymentProviders {

    private final PaymentProvider primary;
    private final Map<PaymentProviderType, PaymentProvider> legacyByType = new EnumMap<>(PaymentProviderType.class);

    @Autowired
    public PaymentProviders(PaymentProvider primary, ObjectProvider<LegacyPaymentProviders> legacy) {
        this(primary, legacy.stream().flatMap(l -> l.providers().stream()).toList());
    }

    public PaymentProviders(PaymentProvider primary, List<PaymentProvider> legacy) {
        this.primary = primary;
        for (PaymentProvider provider : legacy) {
            legacyByType.putIfAbsent(provider.type(), provider);
        }
    }

    /** The provider of new checkouts. */
    public PaymentProvider primary() {
        return primary;
    }

    /** The provider that took (and refunds) payments of this type; {@link PaymentProviderException} if none is known. */
    public PaymentProvider forType(PaymentProviderType type) {
        PaymentProvider provider = primary.type() == type ? primary : legacyByType.get(type);
        if (provider == null) {
            throw new PaymentProviderException("No " + type + " payment provider is configured");
        }
        return provider;
    }

    public PaymentProvider forPayment(Payment payment) {
        return forType(payment.getProvider());
    }
}
