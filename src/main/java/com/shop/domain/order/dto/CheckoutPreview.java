package com.shop.domain.order.dto;

import com.shop.domain.cart.entity.Cart;
import com.shop.domain.coupon.entity.UserCoupon;
import com.shop.domain.user.entity.User;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * [Phase 3 코드 품질] 체크아웃 페이지에 필요한 데이터를 캡슐화하는 DTO.
 *
 * <p><b>문제:</b> 기존 OrderController.checkoutPage()에서 CartService, UserService,
 * CouponService, OrderService를 순차적으로 호출하며 총 6단계의 비즈니스 로직을
 * 컨트롤러 안에서 직접 조합했다. 컨트롤러가 서비스 계층의 오케스트레이션 역할까지
 * 담당하여 단일 책임 원칙이 위반되었다.</p>
 *
 * <p><b>해결:</b> 체크아웃 프리뷰 조합 로직을 {@code CheckoutPreviewService}로 이동하고,
 * 결과를 이 DTO로 반환한다. 컨트롤러는 서비스 호출 + 모델 바인딩만 담당한다.</p>
 */
public record CheckoutPreview(
        List<Cart> cartItems,
        List<Long> cartItemIds,
        BigDecimal totalPrice,
        BigDecimal estimatedShippingFee,
        BigDecimal estimatedFinalAmount,
        User user,
        int pointBalance,
        List<UserCoupon> availableCoupons,
        Map<Long, String> couponDisplayNames
) {
}
