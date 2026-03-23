package com.shop.domain.order.service;

import com.shop.domain.cart.entity.Cart;
import com.shop.domain.cart.repository.CartRepository;
import com.shop.domain.order.dto.OrderCreateRequest;
import com.shop.domain.product.entity.Product;
import com.shop.global.exception.BusinessException;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OrderCartSelectionResolver 단위 테스트.
 *
 * <p>전체/부분 주문 분기, 유효하지 않은 cartItemId 검증, 빈 장바구니 예외,
 * 데드락 방지용 productId 정렬을 격리 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class OrderCartSelectionResolverUnitTest {

    @Mock private CartRepository cartRepository;

    private OrderCartSelectionResolver resolver;

    private static final Long USER_ID = 1L;

    @BeforeEach
    void setUp() {
        resolver = new OrderCartSelectionResolver(cartRepository);
    }

    // ── 픽스처 헬퍼 ──────────────────────────────────────────

    private Product createProduct(Long productId) {
        Product product = Product.create("상품_" + productId,
                mock(com.shop.domain.category.entity.Category.class),
                "설명", new BigDecimal("10000"), new BigDecimal("12000"), 10);
        ReflectionTestUtils.setField(product, "productId", productId);
        return product;
    }

    private Cart createCart(Long cartId, Product product) {
        Cart cart = new Cart(USER_ID, product, 1);
        ReflectionTestUtils.setField(cart, "cartId", cartId);
        return cart;
    }

    /** 전체 주문 요청 (cartItemIds=null) */
    private OrderCreateRequest fullOrderRequest() {
        return new OrderCreateRequest("서울시 강남구", "홍길동", "010-1234-5678",
                "CARD", BigDecimal.ZERO, null, 0, null);
    }

    /** 부분 주문 요청 */
    private OrderCreateRequest partialOrderRequest(List<Long> cartItemIds) {
        return new OrderCreateRequest("서울시 강남구", "홍길동", "010-1234-5678",
                "CARD", BigDecimal.ZERO, null, 0, cartItemIds);
    }

    // ── 락 획득 검증 ─────────────────────────────────────────

    @Test
    @DisplayName("전체/부분 주문 모두 acquireUserCartLock() 항상 1회 호출됨")
    void alwaysAcquiresCartLock() {
        Product product = createProduct(10L);
        Cart cart = createCart(1L, product);
        when(cartRepository.findByUserIdWithProduct(USER_ID))
                .thenReturn(new ArrayList<>(List.of(cart)));

        resolver.resolve(USER_ID, fullOrderRequest());

        verify(cartRepository).acquireUserCartLock(USER_ID);
    }

    // ── 전체 주문 ────────────────────────────────────────────

    @Nested
    @DisplayName("전체 주문 (cartItemIds=null)")
    class FullOrder {

        @Test
        @DisplayName("findByUserIdWithProduct() 호출, isPartialOrder=false")
        void fullOrder_returnsAllCartItems() {
            Product product = createProduct(10L);
            Cart cart = createCart(1L, product);
            when(cartRepository.findByUserIdWithProduct(USER_ID))
                    .thenReturn(new ArrayList<>(List.of(cart)));

            OrderCartSelectionResolver.CartSelection selection =
                    resolver.resolve(USER_ID, fullOrderRequest());

            assertThat(selection.isPartialOrder()).isFalse();
            assertThat(selection.items()).hasSize(1);
            verify(cartRepository).findByUserIdWithProduct(USER_ID);
            verify(cartRepository, never()).findByUserIdAndCartIdIn(anyLong(), anyList());
        }

        @Test
        @DisplayName("빈 장바구니 → BusinessException(EMPTY_CART)")
        void emptyFullCart_throwsBusinessException() {
            when(cartRepository.findByUserIdWithProduct(USER_ID))
                    .thenReturn(new ArrayList<>());

            assertThatThrownBy(() -> resolver.resolve(USER_ID, fullOrderRequest()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("장바구니가 비어있습니다");
        }
    }

    // ── 부분 주문 ────────────────────────────────────────────

    @Nested
    @DisplayName("부분 주문 (cartItemIds 있음)")
    class PartialOrder {

        @Test
        @DisplayName("유효한 항목 → findByUserIdAndCartIdIn() 호출, isPartialOrder=true")
        void partialOrder_returnsSelectedItems() {
            Product product = createProduct(10L);
            Cart cart = createCart(1L, product);
            when(cartRepository.findByUserIdAndCartIdIn(eq(USER_ID), anyList()))
                    .thenReturn(new ArrayList<>(List.of(cart)));

            OrderCartSelectionResolver.CartSelection selection =
                    resolver.resolve(USER_ID, partialOrderRequest(List.of(1L)));

            assertThat(selection.isPartialOrder()).isTrue();
            assertThat(selection.items()).hasSize(1);
            verify(cartRepository, never()).findByUserIdWithProduct(anyLong());
        }

        @Test
        @DisplayName("요청 ID 중 일부가 DB에 없으면 INVALID_CART_SELECTION 예외")
        void invalidCartItemId_throwsBusinessException() {
            Product product = createProduct(10L);
            Cart cart = createCart(1L, product);
            // 요청: [1L, 2L], 조회 결과: [cartId=1L] 만 반환 → mismatch
            when(cartRepository.findByUserIdAndCartIdIn(eq(USER_ID), anyList()))
                    .thenReturn(new ArrayList<>(List.of(cart)));

            assertThatThrownBy(() -> resolver.resolve(USER_ID, partialOrderRequest(List.of(1L, 2L))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("유효하지 않거나 접근 불가한");
        }

        @Test
        @DisplayName("빈 조회 결과 → BusinessException(EMPTY_CART)")
        void emptyPartialCart_throwsBusinessException() {
            when(cartRepository.findByUserIdAndCartIdIn(eq(USER_ID), anyList()))
                    .thenReturn(new ArrayList<>());

            assertThatThrownBy(() -> resolver.resolve(USER_ID, partialOrderRequest(List.of(1L))))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("중복 cartItemIds → 중복 제거 후 단일 조회")
        void duplicateCartItemIds_deduplicatesBeforeQuery() {
            Product product = createProduct(10L);
            Cart cart = createCart(1L, product);
            when(cartRepository.findByUserIdAndCartIdIn(eq(USER_ID), anyList()))
                    .thenReturn(new ArrayList<>(List.of(cart)));

            // 중복 포함 요청
            resolver.resolve(USER_ID, partialOrderRequest(List.of(1L, 1L)));

            // 중복 제거 후 단일 ID로 조회되어야 함
            verify(cartRepository).findByUserIdAndCartIdIn(eq(USER_ID), eq(List.of(1L)));
        }
    }

    // ── 정렬 검증 ────────────────────────────────────────────

    @Test
    @DisplayName("반환된 cartItems가 productId 오름차순으로 정렬됨 (데드락 방지)")
    void sortsByProductIdAscending() {
        Product productA = createProduct(20L); // 높은 ID
        Product productB = createProduct(10L); // 낮은 ID
        Cart cartA = createCart(1L, productA);
        Cart cartB = createCart(2L, productB);

        // 역순으로 반환
        when(cartRepository.findByUserIdWithProduct(USER_ID))
                .thenReturn(new ArrayList<>(List.of(cartA, cartB)));

        OrderCartSelectionResolver.CartSelection selection =
                resolver.resolve(USER_ID, fullOrderRequest());

        List<Long> productIds = selection.items().stream()
                .map(c -> c.getProduct().getProductId())
                .toList();
        assertThat(productIds).isSorted();
    }
}
