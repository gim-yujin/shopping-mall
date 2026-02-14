package com.shop.domain.inventory.service;

import com.shop.domain.inventory.entity.ProductInventoryHistory;
import com.shop.domain.inventory.repository.ProductInventoryHistoryRepository;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.global.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryServiceUnitTest {

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
    @DisplayName("adjustStock - 입고(amount>0) 시 increaseStock 및 IN 이력 저장")
    void adjustStock_positiveAmount_increaseAndSaveHistory() {
        Product product = mock(Product.class);
        when(productRepository.findByIdWithLock(1L)).thenReturn(Optional.of(product));
        when(product.getStockQuantity()).thenReturn(10, 15);

        inventoryService.adjustStock(1L, 5, "TEST_IN", 11L);

        verify(product).increaseStock(5);
        ArgumentCaptor<ProductInventoryHistory> captor = ArgumentCaptor.forClass(ProductInventoryHistory.class);
        verify(historyRepository).save(captor.capture());

        assertThat(captor.getValue().getChangeType())
                .as("입고는 changeType=IN으로 저장되어야 함")
                .isEqualTo("IN");
        assertThat(captor.getValue().getChangeAmount())
                .as("변경 수량은 절대값으로 저장되어야 함")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("adjustStock - 상품이 없으면 ResourceNotFoundException")
    void adjustStock_notFound_throwsException() {
        when(productRepository.findByIdWithLock(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.adjustStock(999L, 3, "TEST", 11L))
                .as("없는 상품 재고 조정 시 예외가 발생해야 함")
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
