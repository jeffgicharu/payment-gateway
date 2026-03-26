package com.gateway.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "webhook_deliveries")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WebhookDelivery {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String transactionId;
    private String merchantId;
    private String url;
    @Column(columnDefinition = "TEXT")
    private String payload;
    private Integer httpStatus;
    @Column(columnDefinition = "TEXT")
    private String responseBody;
    private int attempt;
    private String status;
    private Long durationMs;
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }
}
