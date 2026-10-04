package com.smartparking.admin;

import com.smartparking.admin.dto.AdminListingDetailDto;
import com.smartparking.admin.dto.AdminListingSummaryDto;
import com.smartparking.admin.dto.AdminOwnerDto;
import com.smartparking.admin.dto.QueueCountsDto;
import com.smartparking.availability.AvailabilityRuleRepository;
import com.smartparking.common.config.AppProperties;
import com.smartparking.common.error.ApiException;
import com.smartparking.common.web.PageResponse;
import com.smartparking.email.EmailSender;
import com.smartparking.email.EmailTemplates;
import com.smartparking.listing.ListingMapper;
import com.smartparking.listing.ListingPhoto;
import com.smartparking.listing.ListingPhotoRepository;
import com.smartparking.listing.ListingStatus;
import com.smartparking.listing.ParkingListing;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.listing.dto.ListingDetailDto;
import com.smartparking.owner.OwnerProfile;
import com.smartparking.owner.OwnerProfileRepository;
import com.smartparking.owner.OwnerProfileService;
import com.smartparking.owner.VerificationStatus;
import com.smartparking.slot.ParkingSlotRepository;
import com.smartparking.storage.SignedUrlDto;
import com.smartparking.user.User;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminReviewService {

    private final OwnerProfileRepository ownerProfiles;
    private final OwnerProfileService ownerProfileService;
    private final ParkingListingRepository listings;
    private final ListingPhotoRepository photos;
    private final ParkingSlotRepository slots;
    private final AvailabilityRuleRepository rules;
    private final ListingMapper mapper;
    private final EmailSender emailSender;
    private final AppProperties app;
    private final Clock clock;

    @Transactional(readOnly = true)
    public QueueCountsDto queues() {
        return new QueueCountsDto(ownerProfiles.countByVerificationStatus(VerificationStatus.PENDING),
                listings.countByStatus(ListingStatus.PENDING_REVIEW));
    }

    // ---- owners ----

    @Transactional(readOnly = true)
    public PageResponse<AdminOwnerDto> owners(VerificationStatus status, int page, int size) {
        return PageResponse.from(ownerProfiles
                .findByVerificationStatusOrderByDocumentSubmittedAtAsc(status, pageable(page, size))
                .map(this::toOwnerDto));
    }

    @Transactional(readOnly = true)
    public SignedUrlDto ownerDocumentUrl(Long userId) {
        return ownerProfileService.documentUrl(userId);
    }

    @Transactional
    public AdminOwnerDto verifyOwner(Long userId) {
        OwnerProfile profile = requirePendingOwner(userId);
        profile.setVerificationStatus(VerificationStatus.VERIFIED);
        profile.setVerifiedAt(clock.instant());
        profile.setRejectionReason(null);
        ownerProfiles.save(profile);
        emailSender.send(EmailTemplates.ownerVerified(profile.getUser(), app.frontendUrl() + "/owner/listings"));
        return toOwnerDto(profile);
    }

    @Transactional
    public AdminOwnerDto rejectOwner(Long userId, String reason) {
        OwnerProfile profile = requirePendingOwner(userId);
        String trimmed = reason.trim();
        profile.setVerificationStatus(VerificationStatus.REJECTED);
        profile.setRejectionReason(trimmed);
        ownerProfiles.save(profile);
        emailSender.send(EmailTemplates.ownerRejected(profile.getUser(), trimmed,
                app.frontendUrl() + "/owner/verification"));
        return toOwnerDto(profile);
    }

    private OwnerProfile requirePendingOwner(Long userId) {
        OwnerProfile profile = ownerProfiles.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Owner not found"));
        if (profile.getVerificationStatus() != VerificationStatus.PENDING) {
            throw ApiException.conflict("INVALID_STATUS", "Only owners awaiting review can be verified or rejected");
        }
        return profile;
    }

    private AdminOwnerDto toOwnerDto(OwnerProfile p) {
        User u = p.getUser();
        boolean hasPayout = p.getPayoutUpi() != null
                || (p.getPayoutBankAccount() != null && p.getPayoutIfsc() != null);
        return new AdminOwnerDto(u.getId(), u.getName(), u.getEmail(), u.getPhone(), p.getVerificationStatus(),
                p.getDocumentType(), p.getDocumentSubmittedAt(), p.getRejectionReason(), p.getVerifiedAt(),
                hasPayout, listings.countByOwnerId(u.getId()));
    }

    // ---- listings ----

    @Transactional(readOnly = true)
    public PageResponse<AdminListingSummaryDto> listings(ListingStatus status, int page, int size) {
        return PageResponse.from(listings.findByStatusOrderBySubmittedAtAsc(status, pageable(page, size))
                .map(this::toListingSummary));
    }

    @Transactional(readOnly = true)
    public AdminListingDetailDto listing(Long id) {
        return toDetail(requireListing(id));
    }

    @Transactional
    public AdminListingDetailDto approveListing(Long id) {
        ParkingListing listing = requirePendingListing(id);
        listing.setStatus(ListingStatus.APPROVED);
        listing.setApprovedAt(clock.instant());
        listing.setRejectionReason(null);
        listings.save(listing);
        emailSender.send(EmailTemplates.listingApproved(listing.getOwner(), listing.getTitle(),
                app.frontendUrl() + "/owner/listings"));
        return toDetail(listing);
    }

    @Transactional
    public AdminListingDetailDto rejectListing(Long id, String reason) {
        ParkingListing listing = requirePendingListing(id);
        String trimmed = reason.trim();
        listing.setStatus(ListingStatus.REJECTED);
        listing.setRejectionReason(trimmed);
        listings.save(listing);
        emailSender.send(EmailTemplates.listingRejected(listing.getOwner(), listing.getTitle(), trimmed,
                app.frontendUrl() + "/owner/listings/" + id + "/edit"));
        return toDetail(listing);
    }

    private ParkingListing requireListing(Long id) {
        return listings.findById(id).orElseThrow(() -> ApiException.notFound("Listing not found"));
    }

    private ParkingListing requirePendingListing(Long id) {
        ParkingListing listing = requireListing(id);
        if (listing.getStatus() != ListingStatus.PENDING_REVIEW) {
            throw ApiException.conflict("INVALID_STATUS", "Only listings awaiting review can be approved or rejected");
        }
        return listing;
    }

    private AdminListingSummaryDto toListingSummary(ParkingListing l) {
        List<ListingPhoto> ps = photos.findByListingIdOrderBySortOrderAsc(l.getId());
        String cover = ps.isEmpty() ? null : ps.get(0).getUrl();
        var s = mapper.toSummary(l, cover, slots.countByListingIdAndActiveTrue(l.getId()));
        User o = l.getOwner();
        return new AdminListingSummaryDto(s.id(), s.title(), s.status(), s.cityName(), s.stateName(),
                s.coverPhotoUrl(), s.pricePerHour(), s.slotCount(), s.rejectionReason(), s.updatedAt(),
                o.getId(), o.getName(), o.getEmail(), l.getSubmittedAt());
    }

    private AdminListingDetailDto toDetail(ParkingListing l) {
        ListingDetailDto detail = mapper.toDetail(l, photos.findByListingIdOrderBySortOrderAsc(l.getId()),
                slots.findByListingIdOrderByLabelAsc(l.getId()),
                rules.findByListingIdOrderByDayOfWeekAsc(l.getId()));
        User o = l.getOwner();
        VerificationStatus verification = ownerProfiles.findById(o.getId())
                .map(OwnerProfile::getVerificationStatus).orElse(VerificationStatus.UNSUBMITTED);
        return new AdminListingDetailDto(detail,
                new AdminListingDetailDto.OwnerInfo(o.getId(), o.getName(), o.getEmail(), o.getPhone(), verification));
    }

    private static Pageable pageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }
}
