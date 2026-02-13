package com.shop.domain.inventory.service;

import com.shop.domain.inventory.entity.ProductInventoryHistory;
import com.shop.domain.inventory.repository.ProductInventoryHistoryRepository;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.global.exception.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class InventoryService {

    private final ProductInventoryHistoryRepository historyRepository;
    private final ProductRepository productRepository;

    public InventoryService(ProductInventoryHistoryRepository historyRepository,
                            ProductRepository productRepository) {
        this.historyRepository = historyRepository;
        this.productRepository = productRepository;
    }

    public Page<ProductInventoryHistory> getHistory(Long productId, Pageable pageable) {
        return historyRepository.findByProductIdOrderByCreatedAtDesc(productId, pageable);
    }

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
    }
}
