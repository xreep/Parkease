package com.smartparking.admin.dto;

import com.smartparking.listing.dto.ListingDetailDto;
import com.smartparking.owner.VerificationStatus;

public record AdminListingDetailDto(ListingDetailDto listing, OwnerInfo owner) {

    public record OwnerInfo(Long id, String name, String email, String phone,
                            VerificationStatus verificationStatus) {
    }
}
