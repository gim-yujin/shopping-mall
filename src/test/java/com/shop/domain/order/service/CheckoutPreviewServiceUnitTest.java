package com.shop.domain.order.service;

import com.shop.domain.cart.entity.Cart;
import com.shop.domain.cart.service.CartService;
import com.shop.domain.coupon.entity.Coupon;
import com.shop.domain.coupon.entity.DiscountType;
import com.shop.domain.coupon.entity.UserCoupon;
import com.shop.domain.coupon.service.CouponService;
import com.shop.domain.order.dto.CheckoutPreview;
import com.shop.domain.product.entity.Product;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.entity.UserTier;
import com.shop.domain.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CheckoutPreviewService 단위 테스트.
 *
 * <p>체크아웃 프리뷰 조합 로직과 쿠폰 표시명 포맷(PERCENT/FIXED)을
 * getPreview() 경유로 간접 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class CheckoutPreviewServiceUnitTest {

    @Mock private CartService cartService;
    @Mock private UserService userService;
    @Mock private CouponService couponService;
    @Mock private ShippingFeeCalculator shippingFeeCalculator;

    private CheckoutPreviewService service;

    private static final Long USER_ID = 1L;

    @BeforeEach
    void setUp() {
        service = new CheckoutPreviewService(cartService, userService, couponService, shippingFeeCalculator);
    }

    // ── 픽스처 헬퍼 ──────────────────────────────────────────

    private User createUser() {
        User user = new User("testuser", "test@example.com", "hash", "테스트", "010-0000-0000");
        ReflectionTestUtils.setField(user, "userId", USER_ID);
        ReflectionTestUtils.setField(user, "pointBalance", 500);
        UserTier tier = mock(UserTier.class);
        user.setTier(tier);
        return user;
    }

    private Cart createCart(Long cartId, Long productId) {
        Product product = Product.create("상품_" + productId,
                mock(com.shop.domain.category.entity.Category.class),
                "설명", new BigDecimal("10000"), new BigDecimal("12000"), 10);
        ReflectionTestUtils.setField(product, "productId", productId);
        Cart cart = new Cart(USER_ID, product, 1);
        ReflectionTestUtils.setField(cart, "cartId", cartId);
        return cart;
    }

    private UserCoupon createUserCoupon(Long userCouponId, DiscountType type,
                                         BigDecimal discountValue, BigDecimal minOrderAmount,
                                         String couponName) {
        Coupon coupon = new Coupon("CODE_" + userCouponId, couponName, type,
                discountValue, minOrderAmount, null, null,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30));
        UserCoupon userCoupon = new UserCoupon(USER_ID, coupon, LocalDateTime.now().plusDays(30));
        ReflectionTestUtils.setField(userCoupon, "userCouponId", userCouponId);
        return userCoupon;
    }

    /** 공통 happy-path stub */
    private void stubHappyPath(List<Cart> items, User user) {
        when(userService.findById(USER_ID)).thenReturn(user);
        when(cartService.calculateTotal(items)).thenReturn(new BigDecimal("10000"));
        when(shippingFeeCalculator.calculateShippingFee(any(), any())).thenReturn(new BigDecimal("3000"));
        when(shippingFeeCalculator.calculateFinalAmount(any(), any(), any())).thenReturn(new BigDecimal("13000"));
        when(couponService.getAvailableCoupons(USER_ID)).thenReturn(new ArrayList<>());
    }

    // ── 빈 장바구니 ──────────────────────────────────────────

    @Test
    @DisplayName("빈 장바구니 → null 반환, 이후 서비스 미호출")
    void emptyCart_returnsNull() {
        when(cartService.getSelectedCartItems(USER_ID, null)).thenReturn(new ArrayList<>());

        CheckoutPreview result = service.getPreview(USER_ID, null);

        assertThat(result).isNull();
        verify(userService, never()).findById(anyLong());
        verify(couponService, never()).getAvailableCoupons(anyLong());
    }

    // ── 정상 프리뷰 조합 ─────────────────────────────────────

    @Nested
    @DisplayName("정상 프리뷰 조합")
    class NormalPreview {

        @Test
        @DisplayName("장바구니 있음 → CheckoutPreview 반환 (필드 non-null)")
        void nonEmptyCart_returnsCheckoutPreview() {
            Cart cart = createCart(1L, 10L);
            User user = createUser();
            when(cartService.getSelectedCartItems(USER_ID, null))
                    .thenReturn(new ArrayList<>(List.of(cart)));
            stubHappyPath(List.of(cart), user);

            CheckoutPreview result = service.getPreview(USER_ID, null);

            assertThat(result).isNotNull();
            assertThat(result.cartItems()).isNotNull();
            assertThat(result.user()).isEqualTo(user);
            assertThat(result.pointBalance()).isEqualTo(500);
        }

        @Test
        @DisplayName("cartItemIds=null → getSelectedCartItems(userId, null) 호출")
        void nullCartItemIds_usesFullCart() {
            Cart cart = createCart(1L, 10L);
            User user = createUser();
            when(cartService.getSelectedCartItems(USER_ID, null))
                    .thenReturn(new ArrayList<>(List.of(cart)));
            stubHappyPath(List.of(cart), user);

            service.getPreview(USER_ID, null);

            verify(cartService).getSelectedCartItems(USER_ID, null);
        }

        @Test
        @DisplayName("cartItemIds 있음 → 해당 ID 목록 전달")
        void withCartItemIds_passesIdsToService() {
            List<Long> ids = List.of(1L, 2L);
            Cart cart = createCart(1L, 10L);
            User user = createUser();
            when(cartService.getSelectedCartItems(USER_ID, ids))
                    .thenReturn(new ArrayList<>(List.of(cart)));
            stubHappyPath(List.of(cart), user);

            service.getPreview(USER_ID, ids);

            verify(cartService).getSelectedCartItems(USER_ID, ids);
        }

        @Test
        @DisplayName("preview.cartItemIds() == cart items의 cartId 목록")
        void cartItemIdsMatchCartIds() {
            Cart cartA = createCart(1L, 10L);
            Cart cartB = createCart(2L, 20L);
            User user = createUser();
            List<Cart> items = new ArrayList<>(List.of(cartA, cartB));
            when(cartService.getSelectedCartItems(USER_ID, null)).thenReturn(items);
            stubHappyPath(items, user);

            CheckoutPreview result = service.getPreview(USER_ID, null);

            assertThat(result.cartItemIds()).containsExactly(1L, 2L);
        }

        @Test
        @DisplayName("estimatedFinalAmount → shippingFeeCalculator.calculateFinalAmount() 반환값 반영")
        void finalAmountComputedByCalculator() {
            Cart cart = createCart(1L, 10L);
            User user = createUser();
            when(cartService.getSelectedCartItems(USER_ID, null))
                    .thenReturn(new ArrayList<>(List.of(cart)));
            stubHappyPath(List.of(cart), user);
            when(shippingFeeCalculator.calculateFinalAmount(any(), any(), any()))
                    .thenReturn(new BigDecimal("99999"));

            CheckoutPreview result = service.getPreview(USER_ID, null);

            assertThat(result.estimatedFinalAmount()).isEqualByComparingTo("99999");
        }
    }

    // ── 쿠폰 표시명 포맷 ─────────────────────────────────────

    @Nested
    @DisplayName("쿠폰 표시명 포맷 (buildCouponDisplayNames)")
    class CouponDisplayNames {

        @Test
        @DisplayName("쿠폰 없음 → couponDisplayNames 빈 Map")
        void noCoupons_returnsEmptyMap() {
            Cart cart = createCart(1L, 10L);
            User user = createUser();
            when(cartService.getSelectedCartItems(USER_ID, null))
                    .thenReturn(new ArrayList<>(List.of(cart)));
            stubHappyPath(List.of(cart), user);

            CheckoutPreview result = service.getPreview(USER_ID, null);

            assertThat(result.couponDisplayNames()).isEmpty();
        }

        @Test
        @DisplayName("PERCENT 쿠폰 → '10% 할인, 최소주문(상품금액 기준) 5,000원' 형태")
        void percentCoupon_formatsCorrectly() {
            Cart cart = createCart(1L, 10L);
            User user = createUser();
            UserCoupon percentCoupon = createUserCoupon(1L, DiscountType.PERCENT,
                    new BigDecimal("10.00"), new BigDecimal("5000"), "봄맞이쿠폰");
            when(cartService.getSelectedCartItems(USER_ID, null))
                    .thenReturn(new ArrayList<>(List.of(cart)));
            stubHappyPath(List.of(cart), user);
            when(couponService.getAvailableCoupons(USER_ID))
                    .thenReturn(new ArrayList<>(List.of(percentCoupon)));

            CheckoutPreview result = service.getPreview(USER_ID, null);

            Map<Long, String> names = result.couponDisplayNames();
            assertThat(names).containsKey(1L);
            String displayName = names.get(1L);
            assertThat(displayName)
                    .contains("봄맞이쿠폰")
                    .contains("10%")
                    .contains("5,000원");
        }

        @Test
        @DisplayName("FIXED 쿠폰 → '3,000원 할인, 최소주문(상품금액 기준) 10,000원' 형태")
        void fixedCoupon_formatsCorrectly() {
            Cart cart = createCart(1L, 10L);
            User user = createUser();
            UserCoupon fixedCoupon = createUserCoupon(2L, DiscountType.FIXED,
                    new BigDecimal("3000"), new BigDecimal("10000"), "정액할인쿠폰");
            when(cartService.getSelectedCartItems(USER_ID, null))
                    .thenReturn(new ArrayList<>(List.of(cart)));
            stubHappyPath(List.of(cart), user);
            when(couponService.getAvailableCoupons(USER_ID))
                    .thenReturn(new ArrayList<>(List.of(fixedCoupon)));

            CheckoutPreview result = service.getPreview(USER_ID, null);

            Map<Long, String> names = result.couponDisplayNames();
            assertThat(names).containsKey(2L);
            String displayName = names.get(2L);
            assertThat(displayName)
                    .contains("정액할인쿠폰")
                    .contains("3,000원")
                    .contains("10,000원");
        }

        @Test
        @DisplayName("복수 쿠폰 → LinkedHashMap 삽입 순서 유지 (ID 순)")
        void multipleCoupons_preservesInsertionOrder() {
            Cart cart = createCart(1L, 10L);
            User user = createUser();
            UserCoupon coupon1 = createUserCoupon(1L, DiscountType.PERCENT,
                    new BigDecimal("5"), new BigDecimal("1000"), "쿠폰A");
            UserCoupon coupon2 = createUserCoupon(2L, DiscountType.FIXED,
                    new BigDecimal("1000"), new BigDecimal("5000"), "쿠폰B");
            when(cartService.getSelectedCartItems(USER_ID, null))
                    .thenReturn(new ArrayList<>(List.of(cart)));
            stubHappyPath(List.of(cart), user);
            when(couponService.getAvailableCoupons(USER_ID))
                    .thenReturn(new ArrayList<>(List.of(coupon1, coupon2)));

            CheckoutPreview result = service.getPreview(USER_ID, null);

            assertThat(result.couponDisplayNames().keySet())
                    .containsExactly(1L, 2L);
        }
    }
}
