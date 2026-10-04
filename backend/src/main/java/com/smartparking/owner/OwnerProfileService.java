package com.smartparking.owner;

import com.smartparking.common.error.ApiException;
import com.smartparking.owner.dto.OwnerProfileDto;
import com.smartparking.owner.dto.PayoutRequest;
import com.smartparking.storage.FileStorage;
import com.smartparking.storage.SignedUrlDto;
import com.smartparking.storage.StoredFile;
import com.smartparking.storage.UploadKind;
import com.smartparking.storage.UploadValidator;
import com.smartparking.storage.ValidatedUpload;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class OwnerProfileService {

    static final Duration DOCUMENT_URL_TTL = Duration.ofMinutes(5);

    private final OwnerProfileRepository profiles;
    private final FileStorage storage;
    private final Clock clock;

    @Transactional(readOnly = true)
    public OwnerProfileDto get(Long ownerId) {
        return OwnerProfileDto.from(requireProfile(ownerId));
    }

    @Transactional
    public OwnerProfileDto updatePayout(Long ownerId, PayoutRequest r) {
        String upi = blankToNull(r.upiId());
        String account = blankToNull(r.bankAccount());
        String ifsc = blankToNull(r.ifsc());
        if (upi == null && (account == null || ifsc == null)) {
            throw ApiException.badRequest("PAYOUT_DETAILS_REQUIRED",
                    "Provide a UPI ID, or a bank account number with its IFSC code");
        }
        OwnerProfile profile = requireProfile(ownerId);
        profile.setPayoutUpi(upi);
        profile.setPayoutBankAccount(account);
        profile.setPayoutIfsc(ifsc);
        profile.setPayoutAccountName(r.accountName().trim());
        return OwnerProfileDto.from(profiles.save(profile));
    }

    @Transactional
    public OwnerProfileDto submitDocument(Long ownerId, DocumentType type, MultipartFile file) {
        OwnerProfile profile = requireProfile(ownerId);
        if (profile.getVerificationStatus() == VerificationStatus.VERIFIED) {
            throw ApiException.conflict("ALREADY_VERIFIED", "Your identity is already verified");
        }
        ValidatedUpload upload = UploadValidator.validate(file, UploadKind.DOCUMENT);
        StoredFile stored = storage.storePrivate(upload, "owner-documents");
        String previousKey = profile.getDocumentKey();
        profile.setDocumentType(type);
        profile.setDocumentKey(stored.key());
        profile.setDocumentContentType(stored.contentType());
        profile.setDocumentSubmittedAt(clock.instant());
        profile.setRejectionReason(null);
        profile.setVerificationStatus(VerificationStatus.PENDING);
        OwnerProfile saved = profiles.save(profile);
        storage.delete(previousKey);
        return OwnerProfileDto.from(saved);
    }

    @Transactional(readOnly = true)
    public SignedUrlDto documentUrl(Long ownerId) {
        OwnerProfile profile = requireProfile(ownerId);
        if (profile.getDocumentKey() == null) {
            throw ApiException.notFound("No document uploaded");
        }
        return SignedUrlDto.from(storage.privateUrl(profile.getDocumentKey(), DOCUMENT_URL_TTL));
    }

    @Transactional(readOnly = true)
    public OwnerProfile requireProfile(Long ownerId) {
        return profiles.findById(ownerId).orElseThrow(() -> ApiException.notFound("Owner profile not found"));
    }

    @Transactional(readOnly = true)
    public boolean isVerified(Long ownerId) {
        return requireProfile(ownerId).getVerificationStatus() == VerificationStatus.VERIFIED;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
