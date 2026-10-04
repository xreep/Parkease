package com.smartparking.owner;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OwnerProfileRepository extends JpaRepository<OwnerProfile, Long> {

    Page<OwnerProfile> findByVerificationStatusOrderByDocumentSubmittedAtAsc(VerificationStatus status, Pageable pageable);

    long countByVerificationStatus(VerificationStatus status);
}
