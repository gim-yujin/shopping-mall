package com.shop.domain.order.service;

import com.shop.domain.cart.entity.Cart;
import com.shop.domain.cart.service.CartService;
import com.shop.domain.coupon.entity.Coupon;
import com.shop.domain.coupon.entity.UserCoupon;
import com.shop.domain.coupon.service.CouponService;
import com.shop.domain.order.dto.CheckoutPreview;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * [Phase 3 코드 품질] 체크아웃 페이지 프리뷰 데이터를 조합하는 서비스.
 *
 * <p><b>문제:</b> OrderController.checkoutPage()에서 CartService, UserService,
 * CouponService, OrderService를 순차적으로 호출하며 배송비 계산, 최종 금액 계산,
 * 쿠폰 표시명 생성 등 다단계 비즈니스 로직을 컨트롤러가 직접 수행했다.
 * 이로 인해:
 * <ul>
 *   <li>컨트롤러가 4개 서비스에 의존하여 결합도가 높아짐</li>
 *   <li>컨트롤러 단위 테스트에서 4개 서비스를 모두 mock해야 함</li>
 *   <li>동일한 체크아웃 프리뷰가 다른 컨트롤러(API 등)에서 필요할 때 중복 구현 불가피</li>
 * </ul>
 * </p>
 *
 * <p><b>해결:</b> 체크아웃 프리뷰 조합 로직을 이 서비스로 이동하고,
 * 결과를 {@link CheckoutPreview} DTO로 반환한다. 컨트롤러는 이 서비스 하나만
 * 호출하여 모델에 바인딩하면 된다.</p>
 */
@Service
@Transactional(readOnly = true)
public class CheckoutPreviewService {

    private final CartService cartService;
    private final UserService userService;
    private final CouponService couponService;
    private final ShippingFeeCalculator shippingFeeCalculator;

    public CheckoutPreviewService(CartService cartService,
                                   UserService userService,
                                   CouponService couponService,
                                   ShippingFeeCalculator shippingFeeCalculator) {
        this.cartService = cartService;
        this.userService = userService;
        this.couponService = couponService;
        this.shippingFeeCalculator = shippingFeeCalculator;
    }

    /**
     * 체크아웃 페이지에 필요한 모든 프리뷰 데이터를 조합하여 반환한다.
     *
     * <p>장바구니가 비어있으면 {@code null}을 반환한다.
     * 컨트롤러는 이 경우 장바구니 페이지로 리다이렉트해야 한다.</p>
     *
     * @param userId      현재 로그인 사용자 ID
     * @param cartItemIds 선택 주문 시 장바구니 항목 ID 목록 (null이면 전체 장바구니)
     * @return 체크아웃 프리뷰 데이터, 장바구니가 비어있으면 null
     */
    public CheckoutPreview getPreview(Long userId, List<Long> cartItemIds) {
        List<Cart> items = cartService.getSelectedCartItems(userId, cartItemIds);
        if (items.isEmpty()) {
            return null;
        }

        User user = userService.findById(userId);
        BigDecimal totalPrice = cartService.calculateTotal(items);
        BigDecimal estimatedShippingFee = shippingFeeCalculator.calculateShippingFee(
                user.getTier(), totalPrice);
        BigDecimal estimatedFinalAmount = shippingFeeCalculator.calculateFinalAmount(
                totalPrice, BigDecimal.ZERO, estimatedShippingFee);

        List<UserCoupon> availableCoupons = couponService.getAvailableCoupons(userId);
        Map<Long, String> couponDisplayNames = buildCouponDisplayNames(availableCoupons);

        return new CheckoutPreview(
                items,
                items.stream().map(Cart::getCartId).toList(),
                totalPrice,
                estimatedShippingFee,
                estimatedFinalAmount,
                user,
                user.getPointBalance(),
                availableCoupons,
                couponDisplayNames
        );
    }

    /**
     * 쿠폰 드롭다운에 표시할 사용자 친화적 이름을 생성한다.
     *
     * <p>정률(PERCENT) 쿠폰은 "10% 할인", 정액(FIXED) 쿠폰은 "3,000원 할인" 형식으로
     * 포맷하고, 최소 주문 금액 정보를 함께 표시한다.</p>
     */
    private Map<Long, String> buildCouponDisplayNames(List<UserCoupon> availableCoupons) {
        Map<Long, String> displayNames = new LinkedHashMap<>();
        NumberFormat numberFormat = NumberFormat.getInstance(Locale.KOREA);

        for (UserCoupon userCoupon : availableCoupons) {
            Coupon coupon = userCoupon.getCoupon();
            String discountText = coupon.getDiscountType() == com.shop.domain.coupon.entity.DiscountType.PERCENT
                    ? coupon.getDiscountValue().stripTrailingZeros().toPlainString() + "%"
                    : numberFormat.format(coupon.getDiscountValue()) + "원";

            String displayName = coupon.getCouponName()
                    + " (" + discountText
                    + " 할인, 최소주문(상품금액 기준) " + numberFormat.format(coupon.getMinOrderAmount()) + "원)";

            displayNames.put(userCoupon.getUserCouponId(), displayName);
        }

        return displayNames;
    }
}
