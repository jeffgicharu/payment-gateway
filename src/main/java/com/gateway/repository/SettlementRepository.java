package com.gateway.repository;

import com.gateway.entity.SettlementBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SettlementRepository extends JpaRepository<SettlementBatch, Long> {
    Optional<SettlementBatch> findByBatchId(String batchId);
    List<SettlementBatch> findByMerchantIdOrderByCreatedAtDesc(String merchantId);
}
