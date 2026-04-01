package com.shop.domain.order.service.stock;

import com.shop.domain.product.repository.ProductRepository;
import com.shop.global.exception.InsufficientStockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * V3 CAS UPDATE 재고 차감 전략 단위 테스트.
 *
 * <p>JdbcTemplate과 ProductRepository를 mock하여 정상 차감,
 * 재고 부족(affected=0), beforeStock null 폴백, 정렬 순서를 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class V3CasUpdateStockDeductionTest {

    @Mock private ProductRepository productRepository;
    @Mock private JdbcTemplate jdbcTemplate;

    private V3CasUpdateStockDeduction strategy;

    @BeforeEach
    void setUp() {
        strategy = new V3CasUpdateStockDeduction(productRepository, jdbcTemplate);
    }

    @Test
    @DisplayName("정상 차감 — affected > 0이면 before/after 스냅샷을 반환한다")
    void deductStock_sufficientStock_returnsResults() {
        // beforeStock 조회
        when(jdbcTemplate.queryForObject(
                eq("SELECT stock_quantity FROM products WHERE product_id = ?"),
                eq(Integer.class), eq(1L)))
                .thenReturn(10);

        // CAS UPDATE 성공
        when(productRepository.decreaseStockAtomic(1L, 3)).thenReturn(1);

        List<StockDeductionStrategy.DeductionResult> results = strategy.deductStock(
                List.of(new StockDeductionStrategy.DeductionRequest(1L, 3)));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).beforeStock()).isEqualTo(10);
        assertThat(results.get(0).afterStock()).isEqualTo(7);
    }

    @Test
    @DisplayName("재고 부족 (affected=0) — InsufficientStockException을 던진다")
    void deductStock_insufficientStock_throwsException() {
        // beforeStock 조회
        when(jdbcTemplate.queryForObject(
                eq("SELECT stock_quantity FROM products WHERE product_id = ?"),
                eq(Integer.class), eq(1L)))
                .thenReturn(2);

        // CAS UPDATE 실패 (재고 부족)
        when(productRepository.decreaseStockAtomic(1L, 5)).thenReturn(0);

        // 에러 메시지용 상품명 조회
        when(jdbcTemplate.queryForObject(
                eq("SELECT product_name FROM products WHERE product_id = ?"),
                eq(String.class), eq(1L)))
                .thenReturn("테스트 상품");

        assertThatThrownBy(() -> strategy.deductStock(
                List.of(new StockDeductionStrategy.DeductionRequest(1L, 5))))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    @DisplayName("beforeStock이 null — 0으로 폴백하여 예외 메시지에 포함")
    void deductStock_nullBeforeStock_fallbackToZero() {
        // beforeStock null 반환 (극단적 엣지 케이스)
        when(jdbcTemplate.queryForObject(
                eq("SELECT stock_quantity FROM products WHERE product_id = ?"),
                eq(Integer.class), eq(1L)))
                .thenReturn(null);

        // CAS UPDATE 실패
        when(productRepository.decreaseStockAtomic(1L, 5)).thenReturn(0);

        // 상품명 조회
        when(jdbcTemplate.queryForObject(
                eq("SELECT product_name FROM products WHERE product_id = ?"),
                eq(String.class), eq(1L)))
                .thenReturn("테스트 상품");

        assertThatThrownBy(() -> strategy.deductStock(
                List.of(new StockDeductionStrategy.DeductionRequest(1L, 5))))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    @DisplayName("다건 상품 — productId 오름차순으로 정렬하여 처리한다 (데드락 방지)")
    void deductStock_multipleItems_sortedByProductId() {
        // 역순으로 요청해도 오름차순으로 처리되어야 함
        when(jdbcTemplate.queryForObject(
                eq("SELECT stock_quantity FROM products WHERE product_id = ?"),
                eq(Integer.class), eq(1L)))
                .thenReturn(10);
        when(jdbcTemplate.queryForObject(
                eq("SELECT stock_quantity FROM products WHERE product_id = ?"),
                eq(Integer.class), eq(2L)))
                .thenReturn(20);

        when(productRepository.decreaseStockAtomic(1L, 1)).thenReturn(1);
        when(productRepository.decreaseStockAtomic(2L, 2)).thenReturn(1);

        List<StockDeductionStrategy.DeductionResult> results = strategy.deductStock(
                List.of(
                        new StockDeductionStrategy.DeductionRequest(2L, 2),
                        new StockDeductionStrategy.DeductionRequest(1L, 1)));

        // 정렬된 순서대로 처리: productId 1 → 2
        assertThat(results).hasSize(2);
        assertThat(results.get(0).productId()).isEqualTo(1L);
        assertThat(results.get(1).productId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("strategyName — V3-CAS를 반환한다")
    void strategyName_returnsCorrectName() {
        assertThat(strategy.strategyName()).isEqualTo("V3-CAS");
    }
}
