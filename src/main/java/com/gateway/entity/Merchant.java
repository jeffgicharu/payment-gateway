package com.gateway.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "merchants")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Merchant {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String merchantId;
    private String name;
    private String apiKey;
    private String apiSecret;
    private String callbackUrl;
    private boolean active;
    private BigDecimal dailyLimit;
    private LocalDateTime createdAt;
}
