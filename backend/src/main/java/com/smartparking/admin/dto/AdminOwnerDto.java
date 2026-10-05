package com.smartparking.admin.dto;

import com.smartparking.owner.DocumentType;
import com.smartparking.owner.VerificationStatus;
import java.time.Instant;

public record AdminOwnerDto(
        Long userId,
        String name,
        String email,
        String phone,
        VerificationStatus verificationStatus,
        DocumentType documentType,
        boolean hasDocument,
        Instant documentSubmittedAt,
        String rejectionReason,
        Instant verifiedAt,
        boolean hasPayoutDetails,
        long listingCount) {
}
