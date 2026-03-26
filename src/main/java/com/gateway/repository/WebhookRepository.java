package com.gateway.repository;

import com.gateway.entity.WebhookDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface WebhookRepository extends JpaRepository<WebhookDelivery, Long> {
    List<WebhookDelivery> findByTransactionIdOrderByCreatedAtDesc(String transactionId);
}
