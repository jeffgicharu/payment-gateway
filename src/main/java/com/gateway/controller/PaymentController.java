package com.gateway.controller;

import com.gateway.dto.request.PaymentRequest;
import com.gateway.dto.response.GatewayResponse;
import com.gateway.dto.response.PaymentResponse;
import com.gateway.entity.AuditLog;
import com.gateway.entity.Refund;
import com.gateway.entity.SettlementBatch;
import com.gateway.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Payment processing, reversal, settlement, and analytics")
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    @Operation(summary = "Initiate a payment", description = "Processes M-Pesa STK, card, or bank transfer payment")
    public ResponseEntity<GatewayResponse<PaymentResponse>> initiatePayment(
            @Valid @RequestBody PaymentRequest request,
            HttpServletRequest httpReq) {
        String merchantId = (String) httpReq.getAttribute("merchantId");
        String correlationId = (String) httpReq.getAttribute("correlationId");

        PaymentResponse response = paymentService.initiatePayment(merchantId, request, correlationId);

        int httpStatus = response.getStatus().name().equals("FAILED") ? 400 : 201;
        return ResponseEntity.status(httpStatus)
                .body(GatewayResponse.success(response, correlationId));
    }

    @PostMapping("/{transactionId}/reverse")
    @Operation(summary = "Reverse a completed payment")
    public ResponseEntity<GatewayResponse<PaymentResponse>> reverse(
            @PathVariable String transactionId,
            @RequestParam String reason,
            HttpServletRequest httpReq) {
        String merchantId = (String) httpReq.getAttribute("merchantId");
        String correlationId = (String) httpReq.getAttribute("correlationId");

        PaymentResponse response = paymentService.reversePayment(transactionId, merchantId, reason);
        return ResponseEntity.ok(GatewayResponse.success(response, correlationId));
    }

    @GetMapping("/{transactionId}")
    @Operation(summary = "Get payment status")
    public ResponseEntity<GatewayResponse<PaymentResponse>> getPayment(
            @PathVariable String transactionId, HttpServletRequest httpReq) {
        String correlationId = (String) httpReq.getAttribute("correlationId");
        return ResponseEntity.ok(GatewayResponse.success(
                paymentService.getTransaction(transactionId), correlationId));
    }

    @GetMapping
    @Operation(summary = "List merchant transactions")
    public ResponseEntity<GatewayResponse<Page<PaymentResponse>>> listPayments(
            @PageableDefault(size = 20) Pageable pageable,
            HttpServletRequest httpReq) {
        String merchantId = (String) httpReq.getAttribute("merchantId");
        String correlationId = (String) httpReq.getAttribute("correlationId");
        return ResponseEntity.ok(GatewayResponse.success(
                paymentService.getMerchantTransactions(merchantId, pageable), correlationId));
    }

    @PostMapping("/settle")
    @Operation(summary = "Settle completed transactions", description = "Creates a settlement batch with fee deduction")
    public ResponseEntity<GatewayResponse<SettlementBatch>> settle(HttpServletRequest httpReq) {
        String merchantId = (String) httpReq.getAttribute("merchantId");
        String correlationId = (String) httpReq.getAttribute("correlationId");
        return ResponseEntity.ok(GatewayResponse.success(
                paymentService.settle(merchantId), correlationId));
    }

    @GetMapping("/stats")
    @Operation(summary = "Merchant transaction analytics")
    public ResponseEntity<GatewayResponse<Map<String, Object>>> stats(HttpServletRequest httpReq) {
        String merchantId = (String) httpReq.getAttribute("merchantId");
        String correlationId = (String) httpReq.getAttribute("correlationId");
        return ResponseEntity.ok(GatewayResponse.success(
                paymentService.getMerchantStats(merchantId), correlationId));
    }

    @GetMapping("/audit/{transactionId}")
    @Operation(summary = "Get audit trail for a transaction")
    public ResponseEntity<GatewayResponse<List<AuditLog>>> audit(
            @PathVariable String transactionId, HttpServletRequest httpReq) {
        String correlationId = (String) httpReq.getAttribute("correlationId");
        return ResponseEntity.ok(GatewayResponse.success(
                paymentService.getAuditTrail("TRANSACTION", transactionId), correlationId));
    }

    @PostMapping("/{transactionId}/refund")
    @Operation(summary = "Refund a payment (full or partial)")
    public ResponseEntity<GatewayResponse<Refund>> refund(
            @PathVariable String transactionId,
            @RequestBody java.util.Map<String, Object> body,
            HttpServletRequest httpReq) {
        String merchantId = (String) httpReq.getAttribute("merchantId");
        String correlationId = (String) httpReq.getAttribute("correlationId");
        java.math.BigDecimal amount = new java.math.BigDecimal(body.get("amount").toString());
        String reason = (String) body.getOrDefault("reason", "Merchant initiated refund");

        Refund refund = paymentService.refundPayment(transactionId, merchantId, amount, reason);
        return ResponseEntity.ok(GatewayResponse.success(refund, correlationId));
    }

    @GetMapping("/{transactionId}/refunds")
    @Operation(summary = "List refunds for a transaction")
    public ResponseEntity<GatewayResponse<List<Refund>>> getRefunds(
            @PathVariable String transactionId, HttpServletRequest httpReq) {
        String correlationId = (String) httpReq.getAttribute("correlationId");
        return ResponseEntity.ok(GatewayResponse.success(
                paymentService.getRefunds(transactionId), correlationId));
    }
}
