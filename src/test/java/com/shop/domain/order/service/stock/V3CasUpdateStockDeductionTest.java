package com.shop.domain.order.service.stock;

import com.shop.global.exception.InsufficientStockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * V3 CAS UPDATE 재고 차감 전략 단위 테스트.
 *
 * <p>{@code UPDATE ... RETURNING stock_quantity} 흐름을 검증한다:
 * 정상 차감 시 RETURNING 값을 그대로 afterStock으로 사용하고
 * beforeStock은 quantity를 역산하여 일관된 스냅샷을 만든다.
 * 재고 부족(빈 결과) 시 상품명과 현재 재고를 한 번의 SELECT로 조회하여
 * 의미 있는 예외 메시지를 생성한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class V3CasUpdateStockDeductionTest {

    @Mock private JdbcTemplate jdbcTemplate;

    private V3CasUpdateStockDeduction strategy;

    @BeforeEach
    void setUp() {
        strategy = new V3CasUpdateStockDeduction(jdbcTemplate);
    }

    @Test
    @DisplayName("정상 차감 — RETURNING stock_quantity로 afterStock을 직접 받고 beforeStock을 역산한다")
    void deductStock_sufficientStock_returnsResults() {
        when(jdbcTemplate.queryForList(
                contains("UPDATE products"), eq(Integer.class),
                eq(3), eq(3), eq(1L), eq(3)))
                .thenReturn(List.of(7));

        List<StockDeductionStrategy.DeductionResult> results = strategy.deductStock(
                List.of(new StockDeductionStrategy.DeductionRequest(1L, 3)));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).productId()).isEqualTo(1L);
        assertThat(results.get(0).afterStock()).isEqualTo(7);
        // beforeStock = afterStock + quantity = 7 + 3
        assertThat(results.get(0).beforeStock()).isEqualTo(10);
    }

    @Test
    @DisplayName("재고 부족 — RETURNING 결과가 비어 있으면 상품명/현재 재고를 단일 쿼리로 조회해 예외를 던진다")
    void deductStock_insufficientStock_throwsException() {
        when(jdbcTemplate.queryForList(
                contains("UPDATE products"), eq(Integer.class),
                eq(5), eq(5), eq(1L), eq(5)))
                .thenReturn(List.of());

        when(jdbcTemplate.queryForMap(contains("SELECT product_name"), eq(1L)))
                .thenReturn(Map.of("product_name", "테스트 상품", "stock_quantity", 2));

        assertThatThrownBy(() -> strategy.deductStock(
                List.of(new StockDeductionStrategy.DeductionRequest(1L, 5))))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    @DisplayName("다건 상품 — productId 오름차순으로 정렬하여 처리한다 (데드락 방지)")
    void deductStock_multipleItems_sortedByProductId() {
        when(jdbcTemplate.queryForList(
                contains("UPDATE products"), eq(Integer.class),
                eq(1), eq(1), eq(1L), eq(1)))
                .thenReturn(List.of(9));
        when(jdbcTemplate.queryForList(
                contains("UPDATE products"), eq(Integer.class),
                eq(2), eq(2), eq(2L), eq(2)))
                .thenReturn(List.of(18));

        // 역순으로 요청해도 오름차순으로 처리되어야 함
        List<StockDeductionStrategy.DeductionResult> results = strategy.deductStock(
                List.of(
                        new StockDeductionStrategy.DeductionRequest(2L, 2),
                        new StockDeductionStrategy.DeductionRequest(1L, 1)));

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
