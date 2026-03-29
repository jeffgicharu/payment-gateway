package com.gateway.repository;

import com.gateway.entity.Refund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface RefundRepository extends JpaRepository<Refund, Long> {
    List<Refund> findByOriginalTransactionIdOrderByCreatedAtDesc(String originalTransactionId);

    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM Refund r WHERE r.originalTransactionId = :txnId AND r.status = 'COMPLETED'")
    BigDecimal sumRefundedAmount(@Param("txnId") String originalTransactionId);
}
