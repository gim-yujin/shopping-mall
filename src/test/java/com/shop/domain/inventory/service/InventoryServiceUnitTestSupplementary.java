package com.shop.domain.inventory.service;

import com.shop.domain.inventory.entity.ProductInventoryHistory;
import com.shop.domain.inventory.repository.ProductInventoryHistoryRepository;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * InventoryService 추가 단위 테스트 — adjustStock 출고(amount < 0) 경로
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceUnitTestSupplementary {

    @Mock
    private ProductInventoryHistoryRepository historyRepository;

    @Mock
    private ProductRepository productRepository;

    private InventoryService inventoryService;

    @BeforeEach
    void setUp() {
        inventoryService = new InventoryService(historyRepository, productRepository);
    }

    @Test
    @DisplayName("adjustStock — 출고(amount<0) 시 decreaseStock 및 OUT 이력 저장")
    void adjustStock_negativeAmount_decreaseAndSaveHistory() {
        Product product = mock(Product.class);
        when(productRepository.findByIdWithLock(1L)).thenReturn(Optional.of(product));
        when(product.getStockQuantity()).thenReturn(100, 97);

        inventoryService.adjustStock(1L, -3, "TEST_OUT", 11L);

        verify(product).decreaseStock(3);
        verify(product, never()).increaseStock(anyInt());

        ArgumentCaptor<ProductInventoryHistory> captor = ArgumentCaptor.forClass(ProductInventoryHistory.class);
        verify(historyRepository).save(captor.capture());

        ProductInventoryHistory saved = captor.getValue();
        assertThat(saved.getChangeType())
                .as("출고는 changeType=OUT으로 저장되어야 함")
                .isEqualTo("OUT");
        assertThat(saved.getChangeAmount())
                .as("변경 수량은 절대값으로 저장되어야 함")
                .isEqualTo(3);
        assertThat(saved.getBeforeQuantity()).isEqualTo(100);
        assertThat(saved.getAfterQuantity()).isEqualTo(97);
    }
}
