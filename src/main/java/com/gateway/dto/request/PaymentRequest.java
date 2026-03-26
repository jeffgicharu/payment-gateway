package com.gateway.dto.request;

import com.gateway.enums.PaymentMethod;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class PaymentRequest {
    @NotNull private PaymentMethod paymentMethod;
    @NotNull @DecimalMin("1.00") private BigDecimal amount;
    @NotBlank private String sourceAccount;
    private String destinationAccount;
    @NotBlank private String reference;
    private String description;
    private String callbackUrl;
}
