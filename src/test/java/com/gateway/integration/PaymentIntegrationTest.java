package com.gateway.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private static final String API_KEY = "tk_live_a1b2c3d4e5f6g7h8i9j0";

    @Test
    @DisplayName("Reject request without API key")
    void noApiKey_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethod\":\"MPESA_STK\",\"amount\":1000,\"sourceAccount\":\"+254700000001\",\"reference\":\"nokey\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Accept request with valid API key")
    void validApiKey_processesPayment() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethod\":\"MPESA_STK\",\"amount\":5000,\"sourceAccount\":\"+254700000001\",\"reference\":\"int-001\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.transactionId").exists())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.fee").exists());
    }

    @Test
    @DisplayName("Card payment gets higher fee than M-Pesa")
    void cardFee_higherThanMpesa() throws Exception {
        MvcResult mpesa = mockMvc.perform(post("/api/v1/payments")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethod\":\"MPESA_STK\",\"amount\":10000,\"sourceAccount\":\"+254700000001\",\"reference\":\"fee-mpesa\"}"))
                .andReturn();

        MvcResult card = mockMvc.perform(post("/api/v1/payments")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethod\":\"CARD\",\"amount\":10000,\"sourceAccount\":\"+254700000001\",\"reference\":\"fee-card\"}"))
                .andReturn();

        double mpesaFee = objectMapper.readTree(mpesa.getResponse().getContentAsString())
                .path("data").path("fee").asDouble();
        double cardFee = objectMapper.readTree(card.getResponse().getContentAsString())
                .path("data").path("fee").asDouble();

        org.junit.jupiter.api.Assertions.assertTrue(cardFee > mpesaFee);
    }

    @Test
    @DisplayName("Partial refund deducts from refundable balance")
    void partialRefund_tracksBalance() throws Exception {
        // Create payment
        MvcResult payResult = mockMvc.perform(post("/api/v1/payments")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethod\":\"MPESA_STK\",\"amount\":10000,\"sourceAccount\":\"+254700000001\",\"reference\":\"refund-test\"}"))
                .andReturn();
        String txnId = objectMapper.readTree(payResult.getResponse().getContentAsString())
                .path("data").path("transactionId").asText();

        // Partial refund
        mockMvc.perform(post("/api/v1/payments/" + txnId + "/refund")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":3000,\"reason\":\"Partial return\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").value(3000));

        // Second partial refund
        mockMvc.perform(post("/api/v1/payments/" + txnId + "/refund")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":5000,\"reason\":\"Another return\"}"))
                .andExpect(status().isOk());

        // Over-refund should fail
        mockMvc.perform(post("/api/v1/payments/" + txnId + "/refund")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":5000,\"reason\":\"Too much\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("M-Pesa callback updates transaction status")
    void mpesaCallback_updatesStatus() throws Exception {
        // Create a payment
        MvcResult payResult = mockMvc.perform(post("/api/v1/payments")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethod\":\"MPESA_STK\",\"amount\":2000,\"sourceAccount\":\"+254700000001\",\"reference\":\"cb-test\"}"))
                .andReturn();
        String txnId = objectMapper.readTree(payResult.getResponse().getContentAsString())
                .path("data").path("transactionId").asText();

        // Simulate M-Pesa callback
        mockMvc.perform(post("/api/callbacks/mpesa")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionId\":\"" + txnId + "\",\"resultCode\":\"0\",\"resultDesc\":\"Success\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Transaction lookup returns correct data")
    void getTransaction_returnsData() throws Exception {
        MvcResult payResult = mockMvc.perform(post("/api/v1/payments")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethod\":\"BANK_TRANSFER\",\"amount\":50000,\"sourceAccount\":\"+254700000001\",\"reference\":\"lookup-test\"}"))
                .andReturn();
        String txnId = objectMapper.readTree(payResult.getResponse().getContentAsString())
                .path("data").path("transactionId").asText();

        mockMvc.perform(get("/api/v1/payments/" + txnId)
                .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transactionId").value(txnId))
                .andExpect(jsonPath("$.data.paymentMethod").value("BANK_TRANSFER"));
    }

    @Test
    @DisplayName("Merchant registration returns credentials")
    void registerMerchant_returnsCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/merchants")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"New Shop\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.merchantId").exists())
                .andExpect(jsonPath("$.data.apiKey").exists())
                .andExpect(jsonPath("$.data.apiSecret").exists());
    }

    @Test
    @DisplayName("Settlement creates batch with correct fee breakdown")
    void settle_createsBatchWithFees() throws Exception {
        // Create payments
        mockMvc.perform(post("/api/v1/payments")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethod\":\"MPESA_STK\",\"amount\":5000,\"sourceAccount\":\"+254700000001\",\"reference\":\"settle-1\"}"));
        mockMvc.perform(post("/api/v1/payments")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethod\":\"CARD\",\"amount\":3000,\"sourceAccount\":\"+254700000001\",\"reference\":\"settle-2\"}"));

        // Settle
        mockMvc.perform(post("/api/v1/payments/settle")
                .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transactionCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.data.totalFees").exists())
                .andExpect(jsonPath("$.data.netAmount").exists());
    }

    @Test
    @DisplayName("Stats endpoint returns aggregated data")
    void stats_returnsData() throws Exception {
        mockMvc.perform(get("/api/v1/payments/stats")
                .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.merchantId").value("MCH-TESTSHOP"));
    }

    @Test
    @DisplayName("Correlation ID is returned in response")
    void correlationId_inResponse() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                .header("X-API-Key", API_KEY)
                .header("X-Correlation-ID", "test-corr-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethod\":\"MPESA_STK\",\"amount\":1000,\"sourceAccount\":\"+254700000001\",\"reference\":\"corr-test\"}"))
                .andExpect(header().string("X-Correlation-ID", "test-corr-123"));
    }

    @Test
    @DisplayName("Duplicate reference returns same result via idempotency")
    void duplicateReference_returnsSameResult() throws Exception {
        String body = "{\"paymentMethod\":\"MPESA_STK\",\"amount\":1000,\"sourceAccount\":\"+254700000001\",\"reference\":\"idem-test\"}";

        MvcResult first = mockMvc.perform(post("/api/v1/payments")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andReturn();

        MvcResult second = mockMvc.perform(post("/api/v1/payments")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andReturn();

        String firstTxnId = objectMapper.readTree(first.getResponse().getContentAsString())
                .path("data").path("transactionId").asText();
        String secondTxnId = objectMapper.readTree(second.getResponse().getContentAsString())
                .path("data").path("transactionId").asText();

        org.junit.jupiter.api.Assertions.assertEquals(firstTxnId, secondTxnId);
    }
}
