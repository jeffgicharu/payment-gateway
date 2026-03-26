package com.gateway.entity;

import com.gateway.enums.PaymentMethod;
import com.gateway.enums.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment_transactions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentTransaction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String transactionId;

    private String merchantId;

    @Enumerated(EnumType.STRING)
    private PaymentMethod paymentMethod;

    private BigDecimal amount;
    private String currency;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    private String sourceAccount;
    private String destinationAccount;
    private String reference;
    private String description;
    private String correlationId;
    private String errorCode;
    private String errorMessage;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    private LocalDateTime initiatedAt;
    private LocalDateTime processedAt;
    private LocalDateTime settledAt;

    @PrePersist
    void onCreate() {
        initiatedAt = LocalDateTime.now();
        if (currency == null) currency = "KES";
        if (status == null) status = PaymentStatus.INITIATED;
    }
}
