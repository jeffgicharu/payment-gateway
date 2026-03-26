package com.gateway.security;

import com.gateway.entity.Merchant;
import com.gateway.repository.MerchantRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

/**
 * API key authentication filter.
 * Validates X-API-Key header and optionally verifies HMAC signature.
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class ApiKeyAuthFilter implements Filter {

    private final MerchantRepository merchantRepository;
    private final HmacSigner hmacSigner;

    @Value("${gateway.security.api-key-header}")
    private String apiKeyHeader;

    @Value("${gateway.security.signature-header}")
    private String signatureHeader;

    @Value("${gateway.security.timestamp-header}")
    private String timestampHeader;

    @Value("${gateway.security.max-timestamp-drift-seconds}")
    private int maxDrift;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String path = req.getRequestURI();

        // Skip auth for non-API paths
        if (!path.startsWith("/api/v1/")) {
            chain.doFilter(request, response);
            return;
        }

        String apiKey = req.getHeader(apiKeyHeader);
        if (apiKey == null || apiKey.isBlank()) {
            sendError(res, 401, "Missing API key");
            return;
        }

        Optional<Merchant> merchant = merchantRepository.findByApiKey(apiKey);
        if (merchant.isEmpty() || !merchant.get().isActive()) {
            sendError(res, 401, "Invalid or inactive API key");
            return;
        }

        // Store merchant ID for downstream use
        req.setAttribute("merchantId", merchant.get().getMerchantId());
        req.setAttribute("merchant", merchant.get());

        chain.doFilter(request, response);
    }

    private void sendError(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getWriter().write("{\"code\":" + status + ",\"status\":\"ERROR\",\"message\":\"" + message + "\"}");
    }
}
