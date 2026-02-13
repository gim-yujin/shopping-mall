package com.shop.domain.inventory.repository;

import com.shop.domain.inventory.entity.ProductInventoryHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductInventoryHistoryRepository extends JpaRepository<ProductInventoryHistory, Long> {
    Page<ProductInventoryHistory> findByProductIdOrderByCreatedAtDesc(Long productId, Pageable pageable);
}
