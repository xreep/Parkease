package com.smartparking.auth;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EmailTokenRepository extends JpaRepository<EmailToken, Long> {

    Optional<EmailToken> findByTokenHashAndPurpose(String tokenHash, EmailTokenPurpose purpose);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update EmailToken t set t.usedAt = :now where t.id = :id and t.usedAt is null and t.expiresAt > :now")
    int markUsedIfUsable(Long id, Instant now);

    /** Marks every still-unused token of this purpose as used, so older emailed links stop working. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update EmailToken t set t.usedAt = :now"
            + " where t.user.id = :userId and t.purpose = :purpose and t.usedAt is null")
    int invalidateAll(Long userId, EmailTokenPurpose purpose, Instant now);
}
