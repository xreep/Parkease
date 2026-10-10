package com.smartparking.dispute;

import com.smartparking.common.util.PersonNames;
import com.smartparking.dispute.dto.DisputeDto;
import com.smartparking.earning.OwnerEarning;
import com.smartparking.earning.OwnerEarningRepository;
import com.smartparking.payment.PaymentRepository;
import com.smartparking.payment.RefundService;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Maps disputes to DTOs for the three audiences. Call within a transaction (lazy booking, listing and driver). */
@Component
@RequiredArgsConstructor
public class DisputeMapper {

    public enum Audience { DRIVER, OWNER, ADMIN }

    private final PaymentRepository payments;
    private final RefundService refunds;
    private final OwnerEarningRepository earnings;

    public DisputeDto toDto(Dispute d, Audience audience) {
        boolean admin = audience == Audience.ADMIN;
        String driverName = d.getRaisedBy().getName();
        BigDecimal remaining = admin
                ? refunds.refundableRemaining(payments.findByBookingId(d.getBooking().getId()).orElse(null))
                : null;
        OwnerEarning earning = admin ? earnings.findByBookingId(d.getBooking().getId()).orElse(null) : null;
        return new DisputeDto(d.getId(), d.getBooking().getId(), d.getBooking().getBookingCode(),
                d.getBooking().getListing().getTitle(), d.getCategory(), d.getStatus(), d.getCreatedAt(),
                d.getResolvedAt(), d.getDescription(),
                audience == Audience.OWNER ? PersonNames.firstNameLastInitial(driverName) : driverName,
                d.getOwnerResponse(), d.getOwnerRespondedAt(), d.getResolution(), d.getResolutionAmount(),
                admin ? d.getAdminNotes() : null, remaining, earning == null ? null : earning.getStatus(),
                earning == null ? null : earning.getNet());
    }
}
