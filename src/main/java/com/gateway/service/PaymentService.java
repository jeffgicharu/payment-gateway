package com.gateway.service;

import com.gateway.dto.request.PaymentRequest;
import com.gateway.dto.response.PaymentResponse;
import com.gateway.entity.*;
import com.gateway.enums.PaymentMethod;
import com.gateway.enums.PaymentStatus;
import com.gateway.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final TransactionRepository txnRepo;
    private final MerchantRepository merchantRepo;
    private final SettlementRepository settlementRepo;
    private final AuditLogRepository auditRepo;
    private final RefundRepository refundRepo;
    private final IdempotencyService idempotencyService;
    private final RateLimitService rateLimitService;
    private final WebhookService webhookService;

    private static final BigDecimal FEE_RATE = new BigDecimal("0.015");

    @Transactional
    public PaymentResponse initiatePayment(String merchantId, PaymentRequest request, String correlationId) {
        // Rate limit check
        RateLimitService.RateLimitResult rateCheck = rateLimitService.check(merchantId);
        if (!rateCheck.allowed()) {
            return errorResponse(merchantId, request, correlationId, "RATE_LIMIT_EXCEEDED",
                    "Too many requests. Try again in a moment.");
        }

        // Idempotency check
        String idempotencyKey = merchantId + ":" + request.getReference();
        if (!idempotencyService.tryAcquire(idempotencyKey)) {
            String existingTxnId = idempotencyService.getStoredResult(idempotencyKey);
            if (existingTxnId != null && !existingTxnId.equals("PROCESSING")) {
                return toResponse(txnRepo.findByTransactionId(existingTxnId).orElseThrow());
            }
            return errorResponse(merchantId, request, correlationId, "DUPLICATE_REQUEST",
                    "A payment with this reference is already being processed");
        }

        Merchant merchant = merchantRepo.findByMerchantId(merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Merchant not found"));

        // Daily limit check
        BigDecimal todayTotal = txnRepo.sumCompletedAmountSince(merchantId,
                LocalDate.now().atStartOfDay());
        if (todayTotal.add(request.getAmount()).compareTo(merchant.getDailyLimit()) > 0) {
            return errorResponse(merchantId, request, correlationId, "DAILY_LIMIT_EXCEEDED",
                    "Daily transaction limit exceeded. Limit: " + merchant.getDailyLimit());
        }

        String txnId = generateTransactionId(request.getPaymentMethod());

        PaymentTransaction txn = PaymentTransaction.builder()
                .transactionId(txnId)
                .merchantId(merchantId)
                .paymentMethod(request.getPaymentMethod())
                .amount(request.getAmount())
                .status(PaymentStatus.INITIATED)
                .sourceAccount(request.getSourceAccount())
                .destinationAccount(request.getDestinationAccount())
                .reference(request.getReference())
                .description(request.getDescription())
                .correlationId(correlationId)
                .build();
        txnRepo.save(txn);

        audit("TRANSACTION", txnId, "INITIATED", merchantId, null, txn.getStatus().name());

        processPayment(txn);

        // Store result for idempotency lookups
        idempotencyService.storeResult(idempotencyKey, txnId);

        // Trigger webhook delivery to merchant
        if (merchant.getCallbackUrl() != null && !merchant.getCallbackUrl().isBlank()) {
            webhookService.deliver(txn, merchant.getCallbackUrl(), merchant.getApiSecret());
        }

        log.info("[{}] Payment {} {} {} {} -> {}",
                correlationId, txnId, request.getPaymentMethod(),
                request.getAmount(), request.getSourceAccount(), txn.getStatus());

        return toResponse(txn);
    }

    private void processPayment(PaymentTransaction txn) {
        txn.setStatus(PaymentStatus.PROCESSING);
        txnRepo.save(txn);

        // Simulate processing — in production this calls M-Pesa/card processor
        boolean success = simulatePaymentProcessing(txn);

        if (success) {
            txn.setStatus(PaymentStatus.COMPLETED);
            txn.setProcessedAt(LocalDateTime.now());
            audit("TRANSACTION", txn.getTransactionId(), "COMPLETED", txn.getMerchantId(),
                    "PROCESSING", "COMPLETED");
        } else {
            txn.setStatus(PaymentStatus.FAILED);
            txn.setErrorCode("PROCESSOR_DECLINED");
            txn.setErrorMessage("Payment declined by processor");
            txn.setProcessedAt(LocalDateTime.now());
            audit("TRANSACTION", txn.getTransactionId(), "FAILED", txn.getMerchantId(),
                    "PROCESSING", "FAILED");
        }
        txnRepo.save(txn);
    }

    private boolean simulatePaymentProcessing(PaymentTransaction txn) {
        // Simulate: amounts over 100,000 fail (for demo purposes)
        return txn.getAmount().compareTo(new BigDecimal("100000")) <= 0;
    }

    @Transactional
    public PaymentResponse reversePayment(String transactionId, String merchantId, String reason) {
        PaymentTransaction txn = txnRepo.findByTransactionId(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));

        if (!txn.getMerchantId().equals(merchantId)) {
            throw new IllegalArgumentException("Transaction does not belong to this merchant");
        }
        if (txn.getStatus() != PaymentStatus.COMPLETED) {
            throw new IllegalStateException("Only completed transactions can be reversed. Current: " + txn.getStatus());
        }

        String oldStatus = txn.getStatus().name();
        txn.setStatus(PaymentStatus.REVERSED);
        txn.setErrorMessage("Reversed: " + reason);
        txnRepo.save(txn);

        audit("TRANSACTION", transactionId, "REVERSED", merchantId, oldStatus, "REVERSED");
        return toResponse(txn);
    }

    // ─── REFUNDS ────────────────────────────────────────────────────

    @Transactional
    public Refund refundPayment(String transactionId, String merchantId,
                                BigDecimal refundAmount, String reason) {
        PaymentTransaction txn = txnRepo.findByTransactionId(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));

        if (!txn.getMerchantId().equals(merchantId)) {
            throw new IllegalArgumentException("Transaction does not belong to this merchant");
        }
        if (txn.getStatus() != PaymentStatus.COMPLETED && txn.getStatus() != PaymentStatus.SETTLED) {
            throw new IllegalStateException("Only completed or settled transactions can be refunded. Current: " + txn.getStatus());
        }

        BigDecimal alreadyRefunded = refundRepo.sumRefundedAmount(transactionId);
        BigDecimal maxRefundable = txn.getAmount().subtract(alreadyRefunded);

        if (refundAmount.compareTo(maxRefundable) > 0) {
            throw new IllegalArgumentException(String.format(
                    "Refund amount %s exceeds refundable balance %s (original: %s, already refunded: %s)",
                    refundAmount, maxRefundable, txn.getAmount(), alreadyRefunded));
        }

        String refundId = "RFD-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();

        Refund refund = Refund.builder()
                .refundId(refundId)
                .originalTransactionId(transactionId)
                .merchantId(merchantId)
                .amount(refundAmount)
                .status("COMPLETED")
                .reason(reason)
                .processedAt(LocalDateTime.now())
                .build();
        refundRepo.save(refund);

        // If fully refunded, mark original as reversed
        BigDecimal totalRefunded = alreadyRefunded.add(refundAmount);
        if (totalRefunded.compareTo(txn.getAmount()) >= 0) {
            txn.setStatus(PaymentStatus.REVERSED);
            txnRepo.save(txn);
        }

        audit("REFUND", refundId, "REFUNDED", merchantId,
                "amount=" + refundAmount, "total_refunded=" + totalRefunded);

        log.info("Refund {} created for transaction {}: {} KES (reason: {})",
                refundId, transactionId, refundAmount, reason);

        return refund;
    }

    public List<Refund> getRefunds(String transactionId) {
        return refundRepo.findByOriginalTransactionIdOrderByCreatedAtDesc(transactionId);
    }

    // ─── SETTLEMENT ─────────────────────────────────────────────────

    @Transactional
    public SettlementBatch settle(String merchantId) {
        List<PaymentTransaction> completed = txnRepo.findByMerchantIdAndStatusOrderByInitiatedAtDesc(
                merchantId, PaymentStatus.COMPLETED);

        if (completed.isEmpty()) {
            throw new IllegalStateException("No completed transactions to settle");
        }

        BigDecimal totalAmount = completed.stream()
                .map(PaymentTransaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalFees = totalAmount.multiply(FEE_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal netAmount = totalAmount.subtract(totalFees);

        String batchId = "STL-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();

        SettlementBatch batch = SettlementBatch.builder()
                .batchId(batchId)
                .merchantId(merchantId)
                .transactionCount(completed.size())
                .totalAmount(totalAmount)
                .totalFees(totalFees)
                .netAmount(netAmount)
                .status("SETTLED")
                .settledAt(LocalDateTime.now())
                .build();
        settlementRepo.save(batch);

        // Mark transactions as settled
        completed.forEach(txn -> {
            txn.setStatus(PaymentStatus.SETTLED);
            txn.setSettledAt(LocalDateTime.now());
        });
        txnRepo.saveAll(completed);

        audit("SETTLEMENT", batchId, "SETTLED", merchantId, null,
                completed.size() + " transactions, net " + netAmount);

        return batch;
    }

    // ─── QUERIES ────────────────────────────────────────────────────

    public PaymentResponse getTransaction(String transactionId) {
        return toResponse(txnRepo.findByTransactionId(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found")));
    }

    public Page<PaymentResponse> getMerchantTransactions(String merchantId, Pageable pageable) {
        return txnRepo.findByMerchantIdOrderByInitiatedAtDesc(merchantId, pageable)
                .map(this::toResponse);
    }

    public Map<String, Object> getMerchantStats(String merchantId) {
        Map<String, Object> byStatus = new LinkedHashMap<>();
        for (Object[] row : txnRepo.aggregateByStatus(merchantId)) {
            byStatus.put(row[0].toString(), Map.of("count", row[1], "amount", row[2]));
        }

        Map<String, Object> byMethod = new LinkedHashMap<>();
        for (Object[] row : txnRepo.aggregateByMethod(merchantId)) {
            byMethod.put(row[0].toString(), Map.of("count", row[1], "amount", row[2]));
        }

        BigDecimal todayVolume = txnRepo.sumCompletedAmountSince(merchantId, LocalDate.now().atStartOfDay());

        return Map.of(
                "merchantId", merchantId,
                "byStatus", byStatus,
                "byPaymentMethod", byMethod,
                "todayVolume", todayVolume,
                "settlements", settlementRepo.findByMerchantIdOrderByCreatedAtDesc(merchantId)
        );
    }

    public List<AuditLog> getAuditTrail(String entityType, String entityId) {
        return auditRepo.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType, entityId);
    }

    // ─── HELPERS ────────────────────────────────────────────────────

    private String generateTransactionId(PaymentMethod method) {
        String prefix = switch (method) {
            case MPESA_STK, MPESA_C2B, MPESA_B2C -> "MP";
            case CARD -> "CD";
            case BANK_TRANSFER -> "BT";
        };
        return prefix + System.currentTimeMillis() + new Random().nextInt(1000);
    }

    private PaymentResponse errorResponse(String merchantId, PaymentRequest request,
                                           String correlationId, String errorCode, String errorMessage) {
        return PaymentResponse.builder()
                .merchantId(merchantId)
                .paymentMethod(request.getPaymentMethod())
                .amount(request.getAmount())
                .status(PaymentStatus.FAILED)
                .reference(request.getReference())
                .correlationId(correlationId)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .initiatedAt(LocalDateTime.now())
                .build();
    }

    private PaymentResponse toResponse(PaymentTransaction txn) {
        return PaymentResponse.builder()
                .transactionId(txn.getTransactionId())
                .merchantId(txn.getMerchantId())
                .paymentMethod(txn.getPaymentMethod())
                .amount(txn.getAmount())
                .currency(txn.getCurrency())
                .status(txn.getStatus())
                .reference(txn.getReference())
                .description(txn.getDescription())
                .correlationId(txn.getCorrelationId())
                .errorCode(txn.getErrorCode())
                .errorMessage(txn.getErrorMessage())
                .initiatedAt(txn.getInitiatedAt())
                .processedAt(txn.getProcessedAt())
                .build();
    }

    private void audit(String entityType, String entityId, String action,
                       String actor, String oldValue, String newValue) {
        auditRepo.save(AuditLog.builder()
                .entityType(entityType).entityId(entityId)
                .action(action).actor(actor)
                .oldValue(oldValue).newValue(newValue)
                .build());
    }
}
