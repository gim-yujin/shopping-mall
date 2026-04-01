package com.shop.domain.order.service.stock;

import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.global.exception.InsufficientStockException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * V2 낙관적 잠금 + 재시도 재고 차감 전략 단위 테스트.
 *
 * <p>TransactionTemplate을 mock하여 retry 루프, 재시도 한도 초과,
 * InterruptedException 처리를 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class V2OptimisticRetryStockDeductionTest {

    @Mock private ProductRepository productRepository;
    @Mock private EntityManager entityManager;
    @Mock private TransactionTemplate transactionTemplate;

    private V2OptimisticRetryStockDeduction strategy;

    @BeforeEach
    void setUp() {
        strategy = new V2OptimisticRetryStockDeduction(productRepository, entityManager, transactionTemplate);
    }

    private Product createProduct(Long id, int stock) {
        Product product = Product.create("상품_" + id, null, "설명",
                BigDecimal.valueOf(10000), null, stock);
        ReflectionTestUtils.setField(product, "productId", id);
        return product;
    }

    @Test
    @DisplayName("정상 차감 — 첫 시도에 성공하면 결과를 반환한다")
    void deductStock_firstAttemptSuccess_returnsResults() {
        List<StockDeductionStrategy.DeductionResult> expected = List.of(
                new StockDeductionStrategy.DeductionResult(1L, 10, 7));
        when(transactionTemplate.execute(any())).thenReturn(expected);

        List<StockDeductionStrategy.DeductionRequest> items = List.of(
                new StockDeductionStrategy.DeductionRequest(1L, 3));

        List<StockDeductionStrategy.DeductionResult> results = strategy.deductStock(items);

        assertThat(results).isEqualTo(expected);
        verify(transactionTemplate, times(1)).execute(any());
    }

    @Test
    @DisplayName("낙관적 잠금 충돌 → 재시도 후 성공")
    void deductStock_conflictThenSuccess_retriesAndReturns() {
        AtomicInteger callCount = new AtomicInteger(0);
        List<StockDeductionStrategy.DeductionResult> expected = List.of(
                new StockDeductionStrategy.DeductionResult(1L, 10, 7));

        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            int call = callCount.incrementAndGet();
            if (call <= 2) {
                throw new ObjectOptimisticLockingFailureException(Product.class, 1L);
            }
            return expected;
        });

        List<StockDeductionStrategy.DeductionRequest> items = List.of(
                new StockDeductionStrategy.DeductionRequest(1L, 3));

        List<StockDeductionStrategy.DeductionResult> results = strategy.deductStock(items);

        assertThat(results).isEqualTo(expected);
        verify(transactionTemplate, times(3)).execute(any());
    }

    @Test
    @DisplayName("재시도 한도 초과 (MAX_RETRIES=5) — ObjectOptimisticLockingFailureException을 던진다")
    void deductStock_maxRetriesExceeded_throwsException() {
        when(transactionTemplate.execute(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Product.class, 1L));

        List<StockDeductionStrategy.DeductionRequest> items = List.of(
                new StockDeductionStrategy.DeductionRequest(1L, 3));

        assertThatThrownBy(() -> strategy.deductStock(items))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // 첫 시도 1 + 재시도 5 = 총 6회
        verify(transactionTemplate, times(6)).execute(any());
    }

    @Test
    @DisplayName("재시도 중 InterruptedException — 스레드 인터럽트 플래그를 복원하고 예외를 던진다")
    void deductStock_interruptedDuringBackoff_restoresInterruptAndThrows() {
        when(transactionTemplate.execute(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Product.class, 1L));

        // 테스트 스레드를 인터럽트하여 Thread.sleep()에서 InterruptedException 유발
        Thread.currentThread().interrupt();

        List<StockDeductionStrategy.DeductionRequest> items = List.of(
                new StockDeductionStrategy.DeductionRequest(1L, 3));

        assertThatThrownBy(() -> strategy.deductStock(items))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // 인터럽트 플래그가 복원되어야 한다
        assertThat(Thread.currentThread().isInterrupted()).isTrue();

        // 인터럽트 플래그 정리 (다른 테스트 영향 방지)
        Thread.interrupted();
    }

    @Test
    @DisplayName("strategyName — V2-Optimistic을 반환한다")
    void strategyName_returnsCorrectName() {
        assertThat(strategy.strategyName()).isEqualTo("V2-Optimistic");
    }
}
