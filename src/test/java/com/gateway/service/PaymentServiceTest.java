package com.gateway.service;

import com.gateway.dto.request.PaymentRequest;
import com.gateway.dto.response.PaymentResponse;
import com.gateway.entity.AuditLog;
import com.gateway.entity.SettlementBatch;
import com.gateway.enums.PaymentMethod;
import com.gateway.enums.PaymentStatus;
import com.gateway.security.HmacSigner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class PaymentServiceTest {

    @Autowired private PaymentService paymentService;
    @Autowired private HmacSigner hmacSigner;

    private static final String MERCHANT = "MCH-TESTSHOP";

    private PaymentRequest makeRequest(PaymentMethod method, BigDecimal amount) {
        PaymentRequest req = new PaymentRequest();
        req.setPaymentMethod(method);
        req.setAmount(amount);
        req.setSourceAccount("+254700000001");
        req.setReference("REF-" + System.nanoTime());
        req.setDescription("Test payment");
        return req;
    }

    @Test
    @DisplayName("Should process M-Pesa STK payment successfully")
    void initiate_mpesaStk_success() {
        PaymentResponse res = paymentService.initiatePayment(MERCHANT,
                makeRequest(PaymentMethod.MPESA_STK, new BigDecimal("5000")), "corr-001");
        assertNotNull(res.getTransactionId());
        assertTrue(res.getTransactionId().startsWith("MP"));
        assertEquals(PaymentStatus.COMPLETED, res.getStatus());
        assertEquals("corr-001", res.getCorrelationId());
    }

    @Test
    @DisplayName("Should process card payment successfully")
    void initiate_card_success() {
        PaymentResponse res = paymentService.initiatePayment(MERCHANT,
                makeRequest(PaymentMethod.CARD, new BigDecimal("2500")), "corr-002");
        assertTrue(res.getTransactionId().startsWith("CD"));
        assertEquals(PaymentStatus.COMPLETED, res.getStatus());
    }

    @Test
    @DisplayName("Should process bank transfer successfully")
    void initiate_bankTransfer_success() {
        PaymentResponse res = paymentService.initiatePayment(MERCHANT,
                makeRequest(PaymentMethod.BANK_TRANSFER, new BigDecimal("50000")), "corr-003");
        assertTrue(res.getTransactionId().startsWith("BT"));
        assertEquals(PaymentStatus.COMPLETED, res.getStatus());
    }

    @Test
    @DisplayName("Should fail payment over 100,000 (simulated decline)")
    void initiate_largeAmount_declined() {
        PaymentResponse res = paymentService.initiatePayment(MERCHANT,
                makeRequest(PaymentMethod.MPESA_STK, new BigDecimal("150000")), "corr-004");
        assertEquals(PaymentStatus.FAILED, res.getStatus());
        assertEquals("PROCESSOR_DECLINED", res.getErrorCode());
    }

    @Test
    @DisplayName("Should reverse a completed payment")
    void reverse_completedPayment_success() {
        PaymentResponse completed = paymentService.initiatePayment(MERCHANT,
                makeRequest(PaymentMethod.MPESA_STK, new BigDecimal("3000")), "corr-005");
        assertEquals(PaymentStatus.COMPLETED, completed.getStatus());

        PaymentResponse reversed = paymentService.reversePayment(
                completed.getTransactionId(), MERCHANT, "Customer request");
        assertEquals(PaymentStatus.REVERSED, reversed.getStatus());
    }

    @Test
    @DisplayName("Should not reverse a failed payment")
    void reverse_failedPayment_throws() {
        PaymentResponse failed = paymentService.initiatePayment(MERCHANT,
                makeRequest(PaymentMethod.MPESA_STK, new BigDecimal("200000")), "corr-006");
        assertEquals(PaymentStatus.FAILED, failed.getStatus());

        assertThrows(IllegalStateException.class, () ->
                paymentService.reversePayment(failed.getTransactionId(), MERCHANT, "test"));
    }

    @Test
    @DisplayName("Should settle completed transactions with fees")
    void settle_deductsFees() {
        paymentService.initiatePayment(MERCHANT,
                makeRequest(PaymentMethod.MPESA_STK, new BigDecimal("10000")), "corr-s1");
        paymentService.initiatePayment(MERCHANT,
                makeRequest(PaymentMethod.CARD, new BigDecimal("5000")), "corr-s2");

        SettlementBatch batch = paymentService.settle(MERCHANT);
        assertTrue(batch.getTransactionCount() >= 2);
        assertTrue(batch.getTotalAmount().compareTo(new BigDecimal("15000")) >= 0);
        assertTrue(batch.getTotalFees().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(batch.getNetAmount().compareTo(batch.getTotalAmount()) < 0);
    }

    @Test
    @DisplayName("Should not settle when no completed transactions")
    void settle_noTransactions_throws() {
        // Settle first to clear any existing
        try { paymentService.settle(MERCHANT); } catch (Exception ignored) {}
        assertThrows(IllegalStateException.class, () -> paymentService.settle(MERCHANT));
    }

    @Test
    @DisplayName("Should retrieve transaction by ID")
    void getTransaction_found() {
        PaymentResponse created = paymentService.initiatePayment(MERCHANT,
                makeRequest(PaymentMethod.MPESA_STK, new BigDecimal("1000")), "corr-get");
        PaymentResponse retrieved = paymentService.getTransaction(created.getTransactionId());
        assertEquals(created.getTransactionId(), retrieved.getTransactionId());
    }

    @Test
    @DisplayName("Should maintain audit trail for payment lifecycle")
    void audit_tracksLifecycle() {
        PaymentResponse res = paymentService.initiatePayment(MERCHANT,
                makeRequest(PaymentMethod.MPESA_STK, new BigDecimal("2000")), "corr-audit");

        List<AuditLog> trail = paymentService.getAuditTrail("TRANSACTION", res.getTransactionId());
        assertFalse(trail.isEmpty());
        assertTrue(trail.stream().anyMatch(a -> a.getAction().equals("INITIATED")));
        assertTrue(trail.stream().anyMatch(a -> a.getAction().equals("COMPLETED")));
    }

    @Test
    @DisplayName("Should return merchant statistics")
    void stats_returnsAggregates() {
        paymentService.initiatePayment(MERCHANT,
                makeRequest(PaymentMethod.MPESA_STK, new BigDecimal("8000")), "corr-st1");
        paymentService.initiatePayment(MERCHANT,
                makeRequest(PaymentMethod.CARD, new BigDecimal("3000")), "corr-st2");

        Map<String, Object> stats = paymentService.getMerchantStats(MERCHANT);
        assertEquals(MERCHANT, stats.get("merchantId"));
        assertNotNull(stats.get("byStatus"));
        assertNotNull(stats.get("byPaymentMethod"));
    }

    @Test
    @DisplayName("HMAC signer should sign and verify correctly")
    void hmac_signAndVerify() {
        String data = "1234567890.POST./api/v1/payments.{\"amount\":5000}";
        String secret = "my-secret-key";

        String signature = hmacSigner.sign(data, secret);
        assertNotNull(signature);
        assertTrue(hmacSigner.verify(data, signature, secret));
        assertFalse(hmacSigner.verify(data + "tampered", signature, secret));
    }

    @Test
    @DisplayName("HMAC should reject tampered signatures")
    void hmac_rejectsTampering() {
        String secret = "test-secret";
        String sig = hmacSigner.sign("original-data", secret);
        assertFalse(hmacSigner.verify("modified-data", sig, secret));
    }

    @Test
    @DisplayName("Should prevent cross-merchant reversal")
    void reverse_wrongMerchant_throws() {
        PaymentResponse res = paymentService.initiatePayment(MERCHANT,
                makeRequest(PaymentMethod.MPESA_STK, new BigDecimal("1000")), "corr-cross");
        assertThrows(IllegalArgumentException.class, () ->
                paymentService.reversePayment(res.getTransactionId(), "OTHER-MERCHANT", "test"));
    }
}
