package com.shop.domain.flashsale.repository;

import com.shop.domain.flashsale.entity.FlashSalePurchase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FlashSalePurchaseRepository extends JpaRepository<FlashSalePurchase, Long> {

    boolean existsByFlashSaleIdAndUserId(Long flashSaleId, Long userId);

    /**
     * [Phase 23-5] 주문 취소 보상 경로에서 사용된다.
     * order_id로 구매 로그를 식별해 잔여 수량을 복원할 대상 flash_sale_item을 찾는다.
     */
    Optional<FlashSalePurchase> findByOrderId(Long orderId);
}
