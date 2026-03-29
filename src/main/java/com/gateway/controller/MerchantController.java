package com.gateway.controller;

import com.gateway.dto.response.GatewayResponse;
import com.gateway.entity.Merchant;
import com.gateway.service.MerchantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/merchants")
@RequiredArgsConstructor
@Tag(name = "Merchants", description = "Merchant onboarding and credential management")
public class MerchantController {

    private final MerchantService merchantService;

    @PostMapping
    @Operation(summary = "Register a new merchant")
    public ResponseEntity<GatewayResponse<Map<String, Object>>> register(@Valid @RequestBody RegisterMerchantRequest req) {
        Merchant m = merchantService.register(req.getName(), req.getCallbackUrl(), req.getDailyLimit());
        return ResponseEntity.status(HttpStatus.CREATED).body(GatewayResponse.success(Map.of(
                "merchantId", m.getMerchantId(),
                "apiKey", m.getApiKey(),
                "apiSecret", m.getApiSecret(),
                "message", "Store these credentials securely. The secret cannot be retrieved again."
        ), null));
    }

    @GetMapping
    @Operation(summary = "List all merchants")
    public ResponseEntity<GatewayResponse<List<Merchant>>> list() {
        return ResponseEntity.ok(GatewayResponse.success(merchantService.listAll(), null));
    }

    @GetMapping("/{merchantId}")
    @Operation(summary = "Get merchant details")
    public ResponseEntity<GatewayResponse<Merchant>> get(@PathVariable String merchantId) {
        return ResponseEntity.ok(GatewayResponse.success(merchantService.getByMerchantId(merchantId), null));
    }

    @PutMapping("/{merchantId}/callback")
    @Operation(summary = "Update merchant callback URL")
    public ResponseEntity<GatewayResponse<Merchant>> updateCallback(
            @PathVariable String merchantId, @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(GatewayResponse.success(
                merchantService.updateCallbackUrl(merchantId, body.get("callbackUrl")), null));
    }

    @PostMapping("/{merchantId}/rotate-key")
    @Operation(summary = "Rotate API key and secret")
    public ResponseEntity<GatewayResponse<Map<String, String>>> rotateKey(@PathVariable String merchantId) {
        Merchant m = merchantService.rotateApiKey(merchantId);
        return ResponseEntity.ok(GatewayResponse.success(Map.of(
                "apiKey", m.getApiKey(),
                "apiSecret", m.getApiSecret()
        ), null));
    }

    @PostMapping("/{merchantId}/activate")
    @Operation(summary = "Activate a merchant")
    public ResponseEntity<GatewayResponse<Merchant>> activate(@PathVariable String merchantId) {
        return ResponseEntity.ok(GatewayResponse.success(merchantService.setActive(merchantId, true), null));
    }

    @PostMapping("/{merchantId}/deactivate")
    @Operation(summary = "Deactivate a merchant")
    public ResponseEntity<GatewayResponse<Merchant>> deactivate(@PathVariable String merchantId) {
        return ResponseEntity.ok(GatewayResponse.success(merchantService.setActive(merchantId, false), null));
    }

    @PutMapping("/{merchantId}/daily-limit")
    @Operation(summary = "Update merchant daily transaction limit")
    public ResponseEntity<GatewayResponse<Merchant>> updateLimit(
            @PathVariable String merchantId, @RequestBody Map<String, BigDecimal> body) {
        return ResponseEntity.ok(GatewayResponse.success(
                merchantService.updateDailyLimit(merchantId, body.get("limit")), null));
    }

    @Data
    public static class RegisterMerchantRequest {
        @NotBlank private String name;
        private String callbackUrl;
        private BigDecimal dailyLimit;
    }
}
