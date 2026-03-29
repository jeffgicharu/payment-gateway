package com.gateway.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "refunds", indexes = {
    @Index(name = "idx_refund_txn", columnList = "original_transaction_id"),
    @Index(name = "idx_refund_ref", columnList = "refund_id", unique = true)
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "refund_id", unique = true, nullable = false, length = 30)
    private String refundId;

    @Column(name = "original_transaction_id", nullable = false, length = 30)
    private String originalTransactionId;

    @Column(nullable = false, length = 30)
    private String merchantId;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 255)
    private String reason;

    private LocalDateTime createdAt;

    private LocalDateTime processedAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }
}
