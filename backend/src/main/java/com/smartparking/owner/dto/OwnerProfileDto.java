package com.smartparking.owner.dto;

import com.smartparking.owner.DocumentType;
import com.smartparking.owner.OwnerProfile;
import com.smartparking.owner.VerificationStatus;
import java.time.Instant;

public record OwnerProfileDto(
        VerificationStatus verificationStatus,
        DocumentType documentType,
        boolean hasDocument,
        Instant documentSubmittedAt,
        String rejectionReason,
        Instant verifiedAt,
        String payoutUpi,
        String payoutAccountName,
        String payoutIfsc,
        String payoutBankAccountLast4) {

    public static OwnerProfileDto from(OwnerProfile p) {
        String account = p.getPayoutBankAccount();
        String last4 = account == null || account.isBlank() ? null
                : account.substring(Math.max(0, account.length() - 4));
        return new OwnerProfileDto(p.getVerificationStatus(), p.getDocumentType(), p.getDocumentKey() != null,
                p.getDocumentSubmittedAt(),
                p.getRejectionReason(), p.getVerifiedAt(), p.getPayoutUpi(), p.getPayoutAccountName(),
                p.getPayoutIfsc(), last4);
    }
}
