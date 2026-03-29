package com.gateway.service;

import com.gateway.entity.Merchant;
import com.gateway.repository.MerchantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MerchantService {

    private final MerchantRepository merchantRepo;

    @Transactional
    public Merchant register(String name, String callbackUrl, BigDecimal dailyLimit) {
        String merchantId = "MCH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String apiKey = "tk_live_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String apiSecret = "sk_live_" + UUID.randomUUID().toString().replace("-", "");

        Merchant merchant = Merchant.builder()
                .merchantId(merchantId)
                .name(name)
                .apiKey(apiKey)
                .apiSecret(apiSecret)
                .callbackUrl(callbackUrl)
                .active(true)
                .dailyLimit(dailyLimit != null ? dailyLimit : new BigDecimal("1000000.00"))
                .createdAt(LocalDateTime.now())
                .build();
        merchantRepo.save(merchant);

        log.info("Merchant registered: {} ({})", merchantId, name);
        return merchant;
    }

    public Merchant getByMerchantId(String merchantId) {
        return merchantRepo.findByMerchantId(merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Merchant not found: " + merchantId));
    }

    public List<Merchant> listAll() {
        return merchantRepo.findAll();
    }

    @Transactional
    public Merchant updateCallbackUrl(String merchantId, String callbackUrl) {
        Merchant merchant = getByMerchantId(merchantId);
        merchant.setCallbackUrl(callbackUrl);
        merchantRepo.save(merchant);
        return merchant;
    }

    @Transactional
    public Merchant rotateApiKey(String merchantId) {
        Merchant merchant = getByMerchantId(merchantId);
        merchant.setApiKey("tk_live_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        merchant.setApiSecret("sk_live_" + UUID.randomUUID().toString().replace("-", ""));
        merchantRepo.save(merchant);
        log.info("API key rotated for merchant: {}", merchantId);
        return merchant;
    }

    @Transactional
    public Merchant setActive(String merchantId, boolean active) {
        Merchant merchant = getByMerchantId(merchantId);
        merchant.setActive(active);
        merchantRepo.save(merchant);
        log.info("Merchant {} {}", merchantId, active ? "activated" : "deactivated");
        return merchant;
    }

    @Transactional
    public Merchant updateDailyLimit(String merchantId, BigDecimal limit) {
        Merchant merchant = getByMerchantId(merchantId);
        merchant.setDailyLimit(limit);
        merchantRepo.save(merchant);
        return merchant;
    }
}
