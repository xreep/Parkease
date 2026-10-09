package com.smartparking.payment;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    boolean existsByProviderEventId(String providerEventId);

    Optional<WebhookEvent> findByProviderEventId(String providerEventId);
}
