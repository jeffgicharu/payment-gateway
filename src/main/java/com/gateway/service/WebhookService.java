package com.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.entity.PaymentTransaction;
import com.gateway.entity.WebhookDelivery;
import com.gateway.repository.WebhookRepository;
import com.gateway.security.HmacSigner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Delivers payment status callbacks to merchant webhook URLs.
 *
 * Features:
 * - Async delivery to avoid blocking payment flow
 * - Exponential backoff retry (1s, 2s, 4s)
 * - HMAC-signed payloads so merchants can verify authenticity
 * - Full delivery logging for debugging
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookService {

    private final WebhookRepository webhookRepo;
    private final HmacSigner hmacSigner;
    private final ObjectMapper objectMapper;

    @Value("${gateway.webhook.timeout-ms:5000}")
    private int timeoutMs;

    @Value("${gateway.webhook.max-retries:3}")
    private int maxRetries;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Async
    public void deliver(PaymentTransaction txn, String callbackUrl, String merchantSecret) {
        if (callbackUrl == null || callbackUrl.isBlank()) return;

        String payload = buildPayload(txn);
        String signature = hmacSigner.sign(payload, merchantSecret);

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            WebhookDelivery delivery = attemptDelivery(
                    txn.getTransactionId(), txn.getMerchantId(),
                    callbackUrl, payload, signature, attempt);

            if ("DELIVERED".equals(delivery.getStatus())) {
                log.info("[{}] Webhook delivered to {} (attempt {}, {}ms)",
                        txn.getCorrelationId(), callbackUrl, attempt, delivery.getDurationMs());
                return;
            }

            if (attempt < maxRetries) {
                long backoff = (long) Math.pow(2, attempt - 1) * 1000;
                log.warn("[{}] Webhook delivery failed (attempt {}), retrying in {}ms",
                        txn.getCorrelationId(), attempt, backoff);
                try { Thread.sleep(backoff); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        log.error("[{}] Webhook delivery exhausted all {} retries for {}",
                txn.getCorrelationId(), maxRetries, callbackUrl);
    }

    private WebhookDelivery attemptDelivery(String txnId, String merchantId,
                                             String url, String payload,
                                             String signature, int attempt) {
        long start = System.currentTimeMillis();
        WebhookDelivery delivery = WebhookDelivery.builder()
                .transactionId(txnId)
                .merchantId(merchantId)
                .url(url)
                .payload(payload)
                .attempt(attempt)
                .build();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("X-Webhook-Signature", signature)
                    .header("X-Webhook-Timestamp", Instant.now().toString())
                    .timeout(Duration.ofMillis(timeoutMs))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            delivery.setHttpStatus(response.statusCode());
            delivery.setResponseBody(truncate(response.body(), 1000));
            delivery.setDurationMs(System.currentTimeMillis() - start);
            delivery.setStatus(response.statusCode() < 300 ? "DELIVERED" : "FAILED");

        } catch (Exception e) {
            delivery.setDurationMs(System.currentTimeMillis() - start);
            delivery.setStatus("FAILED");
            delivery.setResponseBody("Error: " + e.getMessage());
        }

        webhookRepo.save(delivery);
        return delivery;
    }

    private String buildPayload(PaymentTransaction txn) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "event", "payment.status_changed",
                    "transactionId", txn.getTransactionId(),
                    "merchantId", txn.getMerchantId(),
                    "status", txn.getStatus().name(),
                    "amount", txn.getAmount(),
                    "currency", txn.getCurrency(),
                    "reference", txn.getReference() != null ? txn.getReference() : "",
                    "timestamp", Instant.now().toString()
            ));
        } catch (Exception e) {
            return "{}";
        }
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }
}
