package com.shop.domain.flashsale.repository;

import com.shop.domain.flashsale.entity.FlashSalePurchase;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlashSalePurchaseRepository extends JpaRepository<FlashSalePurchase, Long> {

    boolean existsByFlashSaleIdAndUserId(Long flashSaleId, Long userId);
}
