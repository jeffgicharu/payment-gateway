package com.gateway.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "settlement_batches")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SettlementBatch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String batchId;
    private String merchantId;
    private int transactionCount;
    private BigDecimal totalAmount;
    private BigDecimal totalFees;
    private BigDecimal netAmount;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime settledAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }
}
