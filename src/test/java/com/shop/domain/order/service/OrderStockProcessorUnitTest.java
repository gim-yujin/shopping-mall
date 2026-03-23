package com.shop.domain.order.service;

import com.shop.domain.cart.entity.Cart;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.global.exception.InsufficientStockException;
import com.shop.global.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * OrderStockProcessor 단위 테스트.
 *
 * <p>재고 차감 로직의 각 분기를 Mock 기반으로 격리 검증한다:
 * 정상 차감, 부족 재고 예외, 상품 미존재 예외, 등급 할인 계산, 재고 스냅샷 정확성.</p>
 */
@ExtendWith(MockitoExtension.class)
class OrderStockProcessorUnitTest {

    @Mock private ProductRepository productRepository;
    @Mock private EntityManager entityManager;

    private OrderStockProcessor processor;

    private static final Long USER_ID = 1L;

    @BeforeEach
    void setUp() {
        processor = new OrderStockProcessor(productRepository, entityManager);
    }

    // ── 픽스처 헬퍼 ──────────────────────────────────────────

    private Product createProduct(Long productId, BigDecimal price, int stock) {
        Product product = Product.create("상품_" + productId,
                mock(com.shop.domain.category.entity.Category.class),
                "설명", price, price.add(new BigDecimal("2000")), stock);
        ReflectionTestUtils.setField(product, "productId", productId);
        return product;
    }

    private Cart createCart(Long cartId, Product product, int quantity) {
        Cart cart = new Cart(USER_ID, product, quantity);
        ReflectionTestUtils.setField(cart, "cartId", cartId);
        return cart;
    }

    // ── 정상 흐름 ───────────────────────────────────────────

    @Nested
    @DisplayName("정상 재고 차감")
    class NormalDeduction {

        @Test
        @DisplayName("단일 상품 — totalAmount, orderLine, inventorySnapshot 정확히 계산")
        void singleItem_returnsCorrectResult() {
            Product product = createProduct(10L, new BigDecimal("10000"), 5);
            Cart cart = createCart(1L, product, 2);

            when(productRepository.findAllByIdInWithLock(anyList()))
                    .thenReturn(new ArrayList<>(List.of(product)));

            OrderStockProcessor.StockDeductionResult result =
                    processor.deductStockAndBuildOrderLines(
                            new ArrayList<>(List.of(cart)), BigDecimal.ZERO);

            assertThat(result.totalAmount()).isEqualByComparingTo("20000");
            assertThat(result.tierDiscountTotal()).isEqualByComparingTo("0");
            assertThat(result.orderLines()).hasSize(1);
            assertThat(result.orderLines().get(0).quantity()).isEqualTo(2);
            assertThat(result.orderLines().get(0).subtotal()).isEqualByComparingTo("20000");
            assertThat(result.inventorySnapshots()).hasSize(1);
        }

        @Test
        @DisplayName("다중 상품 — totalAmount가 각 소계의 합산과 일치")
        void multipleItems_sumsTotalsCorrectly() {
            Product productA = createProduct(10L, new BigDecimal("10000"), 5);
            Product productB = createProduct(20L, new BigDecimal("20000"), 3);
            Cart cartA = createCart(1L, productA, 2);
            Cart cartB = createCart(2L, productB, 1);

            when(productRepository.findAllByIdInWithLock(anyList()))
                    .thenReturn(new ArrayList<>(List.of(productA, productB)));

            OrderStockProcessor.StockDeductionResult result =
                    processor.deductStockAndBuildOrderLines(
                            new ArrayList<>(List.of(cartA, cartB)), BigDecimal.ZERO);

            // 10000*2 + 20000*1 = 40000
            assertThat(result.totalAmount()).isEqualByComparingTo("40000");
            assertThat(result.orderLines()).hasSize(2);
        }

        @Test
        @DisplayName("재고 == 수량인 경계 케이스 — 정상 처리")
        void exactStockMatch_succeeds() {
            Product product = createProduct(10L, new BigDecimal("5000"), 3);
            Cart cart = createCart(1L, product, 3);

            when(productRepository.findAllByIdInWithLock(anyList()))
                    .thenReturn(new ArrayList<>(List.of(product)));

            assertThatCode(() ->
                    processor.deductStockAndBuildOrderLines(
                            new ArrayList<>(List.of(cart)), BigDecimal.ZERO))
                    .doesNotThrowAnyException();
        }
    }

    // ── entityManager.detach() 호출 검증 ────────────────────

    @Test
    @DisplayName("각 cartItem의 product에 대해 entityManager.detach() 호출됨")
    void detachesProductFromContextBeforeLock() {
        Product productA = createProduct(10L, new BigDecimal("10000"), 5);
        Product productB = createProduct(20L, new BigDecimal("10000"), 5);
        Cart cartA = createCart(1L, productA, 1);
        Cart cartB = createCart(2L, productB, 1);

        when(productRepository.findAllByIdInWithLock(anyList()))
                .thenReturn(new ArrayList<>(List.of(productA, productB)));

        processor.deductStockAndBuildOrderLines(
                new ArrayList<>(List.of(cartA, cartB)), BigDecimal.ZERO);

        verify(entityManager).detach(productA);
        verify(entityManager).detach(productB);
    }

    @Test
    @DisplayName("findAllByIdInWithLock() — 정확한 productId 목록으로 1회만 호출됨")
    void callsBatchLockQueryOnce() {
        Product product = createProduct(10L, new BigDecimal("10000"), 5);
        Cart cart = createCart(1L, product, 1);

        when(productRepository.findAllByIdInWithLock(anyList()))
                .thenReturn(new ArrayList<>(List.of(product)));

        processor.deductStockAndBuildOrderLines(
                new ArrayList<>(List.of(cart)), BigDecimal.ZERO);

        verify(productRepository, times(1)).findAllByIdInWithLock(List.of(10L));
    }

    // ── 예외 경로 ────────────────────────────────────────────

    @Nested
    @DisplayName("예외 케이스")
    class ExceptionCases {

        @Test
        @DisplayName("재고 부족 — InsufficientStockException 발생")
        void insufficientStock_throwsException() {
            Product product = createProduct(10L, new BigDecimal("10000"), 1);
            Cart cart = createCart(1L, product, 5); // 요청 5 > 재고 1

            when(productRepository.findAllByIdInWithLock(anyList()))
                    .thenReturn(new ArrayList<>(List.of(product)));

            assertThatThrownBy(() ->
                    processor.deductStockAndBuildOrderLines(
                            new ArrayList<>(List.of(cart)), BigDecimal.ZERO))
                    .isInstanceOf(InsufficientStockException.class);
        }

        @Test
        @DisplayName("잠금 조회 결과에 없는 상품 — ResourceNotFoundException 발생")
        void productNotInLockResult_throwsResourceNotFoundException() {
            Product product = createProduct(10L, new BigDecimal("10000"), 5);
            Cart cart = createCart(1L, product, 1);

            // 잠금 조회 결과가 비어있음 → productMap에 해당 ID 없음
            when(productRepository.findAllByIdInWithLock(anyList()))
                    .thenReturn(new ArrayList<>());

            assertThatThrownBy(() ->
                    processor.deductStockAndBuildOrderLines(
                            new ArrayList<>(List.of(cart)), BigDecimal.ZERO))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── 등급 할인 계산 ───────────────────────────────────────

    @Nested
    @DisplayName("등급 할인 계산")
    class TierDiscount {

        @Test
        @DisplayName("tierDiscountRate=5 → 소계의 5%, 소수점 버림(FLOOR)")
        void tierDiscount5Percent_floorsResult() {
            // 10001 * 5 / 100 = 500.05 → FLOOR → 500
            Product product = createProduct(10L, new BigDecimal("10001"), 5);
            Cart cart = createCart(1L, product, 1);

            when(productRepository.findAllByIdInWithLock(anyList()))
                    .thenReturn(new ArrayList<>(List.of(product)));

            OrderStockProcessor.StockDeductionResult result =
                    processor.deductStockAndBuildOrderLines(
                            new ArrayList<>(List.of(cart)), new BigDecimal("5"));

            assertThat(result.tierDiscountTotal()).isEqualByComparingTo("500");
        }

        @Test
        @DisplayName("tierDiscountRate=0 → tierDiscountTotal이 0")
        void tierDiscountRateZero_noDiscount() {
            Product product = createProduct(10L, new BigDecimal("10000"), 5);
            Cart cart = createCart(1L, product, 1);

            when(productRepository.findAllByIdInWithLock(anyList()))
                    .thenReturn(new ArrayList<>(List.of(product)));

            OrderStockProcessor.StockDeductionResult result =
                    processor.deductStockAndBuildOrderLines(
                            new ArrayList<>(List.of(cart)), BigDecimal.ZERO);

            assertThat(result.tierDiscountTotal()).isEqualByComparingTo("0");
        }
    }

    // ── InventorySnapshot 검증 ───────────────────────────────

    @Test
    @DisplayName("InventorySnapshot — beforeStock/afterStock이 차감 전후 재고를 정확히 기록")
    void inventorySnapshotRecordsBeforeAndAfterStock() {
        int initialStock = 10;
        int orderQty = 3;
        Product product = createProduct(10L, new BigDecimal("10000"), initialStock);
        Cart cart = createCart(1L, product, orderQty);

        when(productRepository.findAllByIdInWithLock(anyList()))
                .thenReturn(new ArrayList<>(List.of(product)));

        OrderStockProcessor.StockDeductionResult result =
                processor.deductStockAndBuildOrderLines(
                        new ArrayList<>(List.of(cart)), BigDecimal.ZERO);

        OrderStockProcessor.InventorySnapshot snapshot = result.inventorySnapshots().get(0);
        assertThat(snapshot.beforeStock()).isEqualTo(initialStock);
        assertThat(snapshot.afterStock()).isEqualTo(initialStock - orderQty);
        assertThat(snapshot.quantity()).isEqualTo(orderQty);
    }
}
