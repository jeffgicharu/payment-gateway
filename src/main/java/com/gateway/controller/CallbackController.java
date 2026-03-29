package com.gateway.controller;

import com.gateway.dto.response.GatewayResponse;
import com.gateway.entity.PaymentTransaction;
import com.gateway.enums.PaymentStatus;
import com.gateway.repository.MerchantRepository;
import com.gateway.repository.TransactionRepository;
import com.gateway.service.WebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Receives async callbacks from payment processors (M-Pesa, card networks).
 * In production, M-Pesa sends a callback to this URL after an STK push completes.
 * This endpoint updates the transaction status and triggers the merchant webhook.
 */
@RestController
@RequestMapping("/api/callbacks")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Callbacks", description = "Async payment processor callback handling")
public class CallbackController {

    private final TransactionRepository txnRepo;
    private final MerchantRepository merchantRepo;
    private final WebhookService webhookService;

    @PostMapping("/mpesa")
    @Operation(summary = "M-Pesa STK Push callback", description = "Called by Safaricom after STK push completes or fails")
    public ResponseEntity<GatewayResponse<String>> mpesaCallback(@RequestBody Map<String, Object> callback) {
        String txnId = (String) callback.get("transactionId");
        String resultCode = String.valueOf(callback.get("resultCode"));
        String resultDesc = (String) callback.getOrDefault("resultDesc", "");

        log.info("M-Pesa callback received: txn={}, resultCode={}, desc={}", txnId, resultCode, resultDesc);

        PaymentTransaction txn = txnRepo.findByTransactionId(txnId).orElse(null);
        if (txn == null) {
            log.warn("Callback for unknown transaction: {}", txnId);
            return ResponseEntity.ok(GatewayResponse.success("Unknown transaction", null));
        }

        if (txn.getStatus() != PaymentStatus.PROCESSING && txn.getStatus() != PaymentStatus.INITIATED) {
            log.info("Callback for already-finalized transaction: {} (status: {})", txnId, txn.getStatus());
            return ResponseEntity.ok(GatewayResponse.success("Already processed", null));
        }

        if ("0".equals(resultCode)) {
            txn.setStatus(PaymentStatus.COMPLETED);
            txn.setProcessedAt(LocalDateTime.now());
        } else {
            txn.setStatus(PaymentStatus.FAILED);
            txn.setErrorCode("MPESA_" + resultCode);
            txn.setErrorMessage(resultDesc);
            txn.setProcessedAt(LocalDateTime.now());
        }
        txnRepo.save(txn);

        // Forward status to merchant
        merchantRepo.findByMerchantId(txn.getMerchantId()).ifPresent(merchant -> {
            if (merchant.getCallbackUrl() != null) {
                webhookService.deliver(txn, merchant.getCallbackUrl(), merchant.getApiSecret());
            }
        });

        return ResponseEntity.ok(GatewayResponse.success("Callback processed", txnId));
    }

    @PostMapping("/card")
    @Operation(summary = "Card processor callback")
    public ResponseEntity<GatewayResponse<String>> cardCallback(@RequestBody Map<String, Object> callback) {
        String txnId = (String) callback.get("transactionId");
        boolean approved = Boolean.TRUE.equals(callback.get("approved"));
        String authCode = (String) callback.getOrDefault("authCode", "");

        log.info("Card callback received: txn={}, approved={}", txnId, approved);

        PaymentTransaction txn = txnRepo.findByTransactionId(txnId).orElse(null);
        if (txn == null) {
            return ResponseEntity.ok(GatewayResponse.success("Unknown transaction", null));
        }

        if (approved) {
            txn.setStatus(PaymentStatus.COMPLETED);
            txn.setMetadata("authCode=" + authCode);
        } else {
            txn.setStatus(PaymentStatus.FAILED);
            txn.setErrorCode("CARD_DECLINED");
            txn.setErrorMessage((String) callback.getOrDefault("declineReason", "Card declined"));
        }
        txn.setProcessedAt(LocalDateTime.now());
        txnRepo.save(txn);

        merchantRepo.findByMerchantId(txn.getMerchantId()).ifPresent(merchant -> {
            if (merchant.getCallbackUrl() != null) {
                webhookService.deliver(txn, merchant.getCallbackUrl(), merchant.getApiSecret());
            }
        });

        return ResponseEntity.ok(GatewayResponse.success("Callback processed", txnId));
    }
}
