package com.smartparking.notification;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);

    Optional<Notification> findByIdAndUserId(Long id, Long userId);

    long countByUserIdAndReadAtIsNull(Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Notification n set n.readAt = :now, n.updatedAt = :now where n.user.id = :userId and n.readAt is null")
    int markAllRead(@Param("userId") Long userId, @Param("now") Instant now);
}
