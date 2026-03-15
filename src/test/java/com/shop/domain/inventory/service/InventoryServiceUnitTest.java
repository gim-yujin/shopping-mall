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

    // [P0 FIX] 재고 조정 후 캐시 무효화를 위한 Outbox 이벤트 발행
    @Mock
    private com.shop.global.outbox.OutboxEventPublisher outboxEventPublisher;

    private InventoryService inventoryService;

    @BeforeEach
    void setUp() {
        inventoryService = new InventoryService(historyRepository, productRepository, outboxEventPublisher);
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

    /**
     * [P0-3 버그 시나리오 재현 테스트] 관리자 재고 조정 후 Outbox 이벤트 발행.
     *
     * <h3>버그 시나리오</h3>
     * <ol>
     *   <li>관리자가 품절 상품에 재고 100개를 추가 (adjustStock +100)</li>
     *   <li>DB의 stock_quantity는 즉시 변경됨</li>
     *   <li>그러나 Outbox 이벤트가 발행되지 않아 productDetail 캐시(TTL 2분)가 무효화되지 않음</li>
     *   <li>사용자는 최대 2분간 "품절" 표시를 계속 봄</li>
     * </ol>
     *
     * <h3>기존 버그</h3>
     * <p>InventoryService.adjustStock()에 outboxEventPublisher.publishStockChanged() 호출이 없었다.
     * 주문 생성/취소 경로에서는 OrderCreationService/OrderCancellationService가 Outbox 이벤트를
     * 발행하지만, 관리자 수동 재고 조정 경로는 이 이벤트 발행이 누락되어 있었다.</p>
     *
     * <h3>수정 후 기대 동작</h3>
     * <p>adjustStock() 완료 후 outboxEventPublisher.publishStockChanged(List.of(productId))가
     * 호출되어, Outbox 폴러가 5초 내에 해당 상품의 캐시를 무효화한다.</p>
     */
    @Test
    @DisplayName("[P0-3 재현] 재고 조정 후 Outbox 캐시 무효화 이벤트가 발행된다")
    void adjustStock_publishesOutboxStockChangedEvent() {
        Product product = mock(Product.class);
        when(productRepository.findByIdWithLock(42L)).thenReturn(Optional.of(product));
        when(product.getStockQuantity()).thenReturn(0, 100);

        inventoryService.adjustStock(42L, 100, "RESTOCK", 1L);

        // 핵심 검증: Outbox 이벤트가 해당 상품 ID로 발행됨
        verify(outboxEventPublisher).publishStockChanged(java.util.List.of(42L));
    }

    /**
     * [P0-3 보완] 출고(음수 조정) 시에도 Outbox 이벤트가 발행되는지 검증.
     *
     * 입고뿐 아니라 출고(수량 감소) 시에도 캐시에 반영되어야 한다.
     * 예: 관리자가 불량품 50개를 재고에서 차감 → 사용자에게 정확한 재고 표시.
     */
    @Test
    @DisplayName("[P0-3 보완] 출고 재고 조정 시에도 Outbox 이벤트가 발행된다")
    void adjustStock_negative_publishesOutboxEvent() {
        Product product = mock(Product.class);
        when(productRepository.findByIdWithLock(7L)).thenReturn(Optional.of(product));
        when(product.getStockQuantity()).thenReturn(200, 150);

        inventoryService.adjustStock(7L, -50, "DEFECT_REMOVAL", 1L);

        verify(outboxEventPublisher).publishStockChanged(java.util.List.of(7L));
    }
}
