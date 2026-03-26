package com.gateway.dto.response;

import com.gateway.enums.PaymentMethod;
import com.gateway.enums.PaymentStatus;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data @Builder @AllArgsConstructor
public class PaymentResponse {
    private String transactionId;
    private String merchantId;
    private PaymentMethod paymentMethod;
    private BigDecimal amount;
    private String currency;
    private PaymentStatus status;
    private String reference;
    private String description;
    private String correlationId;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime initiatedAt;
    private LocalDateTime processedAt;
}
