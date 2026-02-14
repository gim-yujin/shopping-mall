package com.shop.domain.product.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

/**
 * Product 엔티티 단위 테스트 — 재고 증감, 조회수, 할인율 계산
 */
class ProductEntityUnitTest {

    private Product createProduct(int stock, int salesCount) throws Exception {
        Product product = Product.class.getDeclaredConstructor().newInstance();
        setField(product, "stockQuantity", stock);
        setField(product, "salesCount", salesCount);
        setField(product, "viewCount", 0);
        return product;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ==================== decreaseStock ====================

    @Test
    @DisplayName("decreaseStock — 정상 차감: 재고 감소 + 판매 수량 증가")
    void decreaseStock_success() throws Exception {
        Product product = createProduct(100, 50);

        product.decreaseStock(10);

        assertThat(product.getStockQuantity()).isEqualTo(90);
        assertThat(product.getSalesCount()).isEqualTo(60);
    }

    @Test
    @DisplayName("decreaseStock — 재고 전량 차감 가능")
    void decreaseStock_exactStock() throws Exception {
        Product product = createProduct(5, 0);

        product.decreaseStock(5);

        assertThat(product.getStockQuantity()).isEqualTo(0);
        assertThat(product.getSalesCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("decreaseStock — 재고 부족 시 IllegalStateException")
    void decreaseStock_insufficientStock_throwsException() throws Exception {
        Product product = createProduct(3, 0);

        assertThatThrownBy(() -> product.decreaseStock(5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("재고가 부족");
    }

    // ==================== increaseStock ====================

    @Test
    @DisplayName("increaseStock — 재고 증가")
    void increaseStock_success() throws Exception {
        Product product = createProduct(100, 0);

        product.increaseStock(50);

        assertThat(product.getStockQuantity()).isEqualTo(150);
    }

    // ==================== incrementViewCount ====================

    @Test
    @DisplayName("incrementViewCount — 조회수 1 증가")
    void incrementViewCount_incrementsByOne() throws Exception {
        Product product = createProduct(100, 0);

        product.incrementViewCount();
        assertThat(product.getViewCount()).isEqualTo(1);

        product.incrementViewCount();
        assertThat(product.getViewCount()).isEqualTo(2);
    }

    // ==================== getDiscountPercent ====================

    @Test
    @DisplayName("getDiscountPercent — 원래 가격 대비 할인율 계산")
    void getDiscountPercent_calculatesCorrectly() throws Exception {
        Product product = createProduct(100, 0);
        setField(product, "price", BigDecimal.valueOf(80000));
        setField(product, "originalPrice", BigDecimal.valueOf(100000));

        assertThat(product.getDiscountPercent()).isEqualTo(20);
    }

    @Test
    @DisplayName("getDiscountPercent — originalPrice가 null이면 0")
    void getDiscountPercent_noOriginalPrice_returnsZero() throws Exception {
        Product product = createProduct(100, 0);
        setField(product, "price", BigDecimal.valueOf(80000));
        setField(product, "originalPrice", null);

        assertThat(product.getDiscountPercent()).isEqualTo(0);
    }

    @Test
    @DisplayName("getDiscountPercent — 할인 없는 상품(price == originalPrice)은 0")
    void getDiscountPercent_samePrice_returnsZero() throws Exception {
        Product product = createProduct(100, 0);
        setField(product, "price", BigDecimal.valueOf(50000));
        setField(product, "originalPrice", BigDecimal.valueOf(50000));

        assertThat(product.getDiscountPercent()).isEqualTo(0);
    }
}
