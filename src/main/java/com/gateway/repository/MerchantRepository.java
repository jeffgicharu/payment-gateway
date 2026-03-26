package com.gateway.repository;

import com.gateway.entity.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MerchantRepository extends JpaRepository<Merchant, Long> {
    Optional<Merchant> findByApiKey(String apiKey);
    Optional<Merchant> findByMerchantId(String merchantId);
}
