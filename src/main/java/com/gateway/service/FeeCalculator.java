package com.gateway.service;

import com.gateway.enums.PaymentMethod;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Calculates processing fees per payment method.
 * M-Pesa has the lowest fees because it's Safaricom's own rail.
 * Card payments cost more because of interchange fees.
 * Bank transfers are cheapest for large amounts.
 */
@Component
public class FeeCalculator {

    public BigDecimal calculate(PaymentMethod method, BigDecimal amount) {
        BigDecimal rate = getRate(method);
        BigDecimal fee = amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal min = getMinimumFee(method);
        return fee.max(min);
    }

    public BigDecimal getRate(PaymentMethod method) {
        return switch (method) {
            case MPESA_STK, MPESA_C2B -> new BigDecimal("0.010");  // 1.0%
            case MPESA_B2C -> new BigDecimal("0.015");              // 1.5%
            case CARD -> new BigDecimal("0.025");                   // 2.5%
            case BANK_TRANSFER -> new BigDecimal("0.005");          // 0.5%
        };
    }

    public BigDecimal getMinimumFee(PaymentMethod method) {
        return switch (method) {
            case MPESA_STK, MPESA_C2B, MPESA_B2C -> new BigDecimal("5.00");
            case CARD -> new BigDecimal("10.00");
            case BANK_TRANSFER -> new BigDecimal("25.00");
        };
    }
}
