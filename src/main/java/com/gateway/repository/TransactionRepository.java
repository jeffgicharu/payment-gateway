package com.gateway.repository;

import com.gateway.entity.PaymentTransaction;
import com.gateway.enums.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<PaymentTransaction, Long> {
    Optional<PaymentTransaction> findByTransactionId(String transactionId);
    boolean existsByTransactionId(String transactionId);
    Page<PaymentTransaction> findByMerchantIdOrderByInitiatedAtDesc(String merchantId, Pageable pageable);
    List<PaymentTransaction> findByMerchantIdAndStatusOrderByInitiatedAtDesc(String merchantId, PaymentStatus status);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM PaymentTransaction t WHERE t.merchantId = :mid AND t.status = 'COMPLETED' AND t.initiatedAt >= :since")
    BigDecimal sumCompletedAmountSince(@Param("mid") String merchantId, @Param("since") LocalDateTime since);

    @Query("SELECT t.status, COUNT(t), COALESCE(SUM(t.amount), 0) FROM PaymentTransaction t WHERE t.merchantId = :mid GROUP BY t.status")
    List<Object[]> aggregateByStatus(@Param("mid") String merchantId);

    @Query("SELECT t.paymentMethod, COUNT(t), COALESCE(SUM(t.amount), 0) FROM PaymentTransaction t WHERE t.merchantId = :mid AND t.status = 'COMPLETED' GROUP BY t.paymentMethod")
    List<Object[]> aggregateByMethod(@Param("mid") String merchantId);
}
