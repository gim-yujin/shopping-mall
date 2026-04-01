package com.shop.domain.order.service.stock;

import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.global.exception.InsufficientStockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * V1 비관적 잠금 재고 차감 전략 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class V1PessimisticLockStockDeductionTest {

    @Mock
    private ProductRepository productRepository;

    private V1PessimisticLockStockDeduction strategy;

    @BeforeEach
    void setUp() {
        strategy = new V1PessimisticLockStockDeduction(productRepository);
    }

    private Product createProduct(Long id, int stock) {
        Product product = Product.create("상품_" + id, null, "설명",
                BigDecimal.valueOf(10000), null, stock);
        ReflectionTestUtils.setField(product, "productId", id);
        return product;
    }

    @Test
    @DisplayName("정상 차감 — 재고가 충분하면 before/after 스냅샷을 반환한다")
    void deductStock_sufficientStock_returnsResults() {
        Product p1 = createProduct(1L, 10);
        Product p2 = createProduct(2L, 20);
        when(productRepository.findAllByIdInWithLock(anyList()))
                .thenReturn(new ArrayList<>(List.of(p1, p2)));

        List<StockDeductionStrategy.DeductionRequest> items = List.of(
                new StockDeductionStrategy.DeductionRequest(1L, 3),
                new StockDeductionStrategy.DeductionRequest(2L, 5));

        List<StockDeductionStrategy.DeductionResult> results = strategy.deductStock(items);

        assertThat(results).hasSize(2);
        assertThat(results.get(0)).satisfies(r -> {
            assertThat(r.productId()).isEqualTo(1L);
            assertThat(r.beforeStock()).isEqualTo(10);
            assertThat(r.afterStock()).isEqualTo(7);
        });
        assertThat(results.get(1)).satisfies(r -> {
            assertThat(r.productId()).isEqualTo(2L);
            assertThat(r.beforeStock()).isEqualTo(20);
            assertThat(r.afterStock()).isEqualTo(15);
        });
    }

    @Test
    @DisplayName("재고 부족 — InsufficientStockException을 던진다")
    void deductStock_insufficientStock_throwsException() {
        Product p1 = createProduct(1L, 2);
        when(productRepository.findAllByIdInWithLock(anyList()))
                .thenReturn(new ArrayList<>(List.of(p1)));

        List<StockDeductionStrategy.DeductionRequest> items = List.of(
                new StockDeductionStrategy.DeductionRequest(1L, 5));

        assertThatThrownBy(() -> strategy.deductStock(items))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    @DisplayName("strategyName — V1-Pessimistic을 반환한다")
    void strategyName_returnsCorrectName() {
        assertThat(strategy.strategyName()).isEqualTo("V1-Pessimistic");
    }
}
