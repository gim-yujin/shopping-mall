package com.shop.domain.inventory.service;

import com.shop.domain.inventory.entity.ProductInventoryHistory;
import com.shop.domain.inventory.repository.ProductInventoryHistoryRepository;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.global.exception.ResourceNotFoundException;
import com.shop.global.outbox.OutboxEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class InventoryService {

    private final ProductInventoryHistoryRepository historyRepository;
    private final ProductRepository productRepository;
    private final OutboxEventPublisher outboxEventPublisher;

    public InventoryService(ProductInventoryHistoryRepository historyRepository,
                            ProductRepository productRepository,
                            OutboxEventPublisher outboxEventPublisher) {
        this.historyRepository = historyRepository;
        this.productRepository = productRepository;
        this.outboxEventPublisher = outboxEventPublisher;
    }

    public Page<ProductInventoryHistory> getHistory(Long productId, Pageable pageable) {
        return historyRepository.findByProductIdOrderByCreatedAtDesc(productId, pageable);
    }

    /**
     * 관리자 재고 수동 조정.
     *
     * [P0 BUG FIX] 재고 조정 후 Outbox 이벤트 미발행으로 인한 캐시 불일치 수정.
     *
     * 기존 문제: 관리자가 재고를 수동 조정하면 DB의 stock_quantity는 즉시 변경되지만,
     * Outbox 이벤트가 발행되지 않아 productDetail 캐시(TTL 2분)가 무효화되지 않았다.
     * 결과적으로 사용자는 최대 2분간 변경 전의 재고 정보를 보게 되었다.
     * 특히 품절 상품의 재고를 추가하거나, 긴급 재고 차감 시 사용자에게
     * 잘못된 정보가 표시되는 문제가 있었다.
     *
     * 수정: 재고 조정 후 outboxEventPublisher.publishStockChanged()를 호출하여
     * 주문 생성/취소와 동일한 캐시 무효화 경로를 사용한다.
     * Outbox 폴러가 5초 간격으로 이벤트를 처리하므로 캐시 반영까지 최대 ~5초.
     */
    @Transactional
    public void adjustStock(Long productId, int amount, String reason, Long userId) {
        Product product = productRepository.findByIdWithLock(productId)
                .orElseThrow(() -> new ResourceNotFoundException("상품", productId));
        int before = product.getStockQuantity();
        String type = amount > 0 ? "IN" : "OUT";
        if (amount > 0) {
            product.increaseStock(amount);
        } else {
            product.decreaseStock(Math.abs(amount));
        }
        historyRepository.save(new ProductInventoryHistory(
                productId, type, Math.abs(amount), before,
                product.getStockQuantity(), reason, null, userId));

        // [P0 FIX] 재고 변경 Outbox 이벤트 발행 → 상품 상세 캐시 무효화
        outboxEventPublisher.publishStockChanged(List.of(productId));
    }
}
