package com.shop.domain.product.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProductListReadModel 매핑·soldOut() 헬퍼 검증.
 *
 * v_product_list 뷰의 컬럼 순서가 13개로 확장됐고(stock_quantity 추가),
 * fromNativeRow가 마지막 인덱스를 stockQuantity로 매핑한다.
 */
class ProductListReadModelTest {

    @Test
    @DisplayName("fromNativeRow — stock_quantity 컬럼 매핑 + soldOut 판정")
    void fromNativeRow_mapsStockQuantity() {
        Object[] row = baseRow(50);

        ProductListReadModel model = ProductListReadModel.fromNativeRow(row);

        assertThat(model.stockQuantity()).isEqualTo(50);
        assertThat(model.soldOut()).isFalse();
    }

    @Test
    @DisplayName("soldOut — stock_quantity=0이면 true")
    void soldOut_whenZeroStock() {
        ProductListReadModel model = ProductListReadModel.fromNativeRow(baseRow(0));

        assertThat(model.soldOut()).isTrue();
    }

    @Test
    @DisplayName("soldOut — stock_quantity null이면 false (보수적: 알 수 없으면 노출)")
    void soldOut_nullStock_isFalse() {
        ProductListReadModel model = ProductListReadModel.fromNativeRow(baseRow(null));

        assertThat(model.stockQuantity()).isNull();
        assertThat(model.soldOut()).isFalse();
    }

    private Object[] baseRow(Integer stock) {
        return new Object[]{
                1L, "테스트 상품",
                new BigDecimal("10000"), new BigDecimal("12000"),
                new BigDecimal("4.50"), 25, 100,
                1, "전자기기",
                Timestamp.valueOf(LocalDateTime.now()),
                "/images/thumb.jpg", true,
                stock
        };
    }
}
