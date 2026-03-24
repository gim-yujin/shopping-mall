package com.shop.domain.order.service;

import com.shop.domain.cart.entity.Cart;
import com.shop.domain.cart.service.CartService;
import com.shop.domain.coupon.entity.Coupon;
import com.shop.domain.coupon.entity.UserCoupon;
import com.shop.domain.coupon.service.CouponService;
import com.shop.domain.order.dto.CheckoutPreview;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.service.UserService;
import com.shop.global.resilience.ResilientCallExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Collections;
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
 *
 * <h3>[Resilience4j] 크로스 도메인 호출 장애 격리</h3>
 * <p>{@link ResilientCallExecutor}를 사용하여 장바구니·사용자·쿠폰 서비스 호출에
 * 개별 서킷 브레이커 + 타임아웃을 적용한다.
 * 각 서비스가 독립적인 서킷을 가지므로, 쿠폰 서비스 장애가
 * 장바구니/사용자 서비스 호출에 영향을 주지 않는다.</p>
 * <ul>
 *   <li><b>장바구니/사용자</b>: 필수 데이터이므로 폴백 없이 예외를 전파한다.</li>
 *   <li><b>쿠폰</b>: 비필수 데이터이므로 장애 시 빈 목록으로 폴백하여
 *       쿠폰 없이 체크아웃을 계속 진행할 수 있다.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class CheckoutPreviewService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutPreviewService.class);

    private final CartService cartService;
    private final UserService userService;
    private final CouponService couponService;
    private final ShippingFeeCalculator shippingFeeCalculator;

    /** 크로스 도메인 호출에 Timeout + Circuit Breaker를 적용하는 실행기 */
    private final ResilientCallExecutor resilientCallExecutor;

    public CheckoutPreviewService(CartService cartService,
                                   UserService userService,
                                   CouponService couponService,
                                   ShippingFeeCalculator shippingFeeCalculator,
                                   ResilientCallExecutor resilientCallExecutor) {
        this.cartService = cartService;
        this.userService = userService;
        this.couponService = couponService;
        this.shippingFeeCalculator = shippingFeeCalculator;
        this.resilientCallExecutor = resilientCallExecutor;
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
        // [Resilience4j] 장바구니 서비스 호출 — Timeout(3s) + CircuitBreaker 적용.
        // 장바구니는 체크아웃의 필수 데이터이므로 폴백 없이 예외를 전파한다.
        // 서킷 OPEN 시 CallNotPermittedException이 컨트롤러까지 전파되어
        // GlobalExceptionHandler에서 "서비스 일시 불안정" 응답을 반환한다.
        List<Cart> items = resilientCallExecutor.execute("cartService",
                () -> cartService.getSelectedCartItems(userId, cartItemIds));
        if (items.isEmpty()) {
            return null;
        }

        // [Resilience4j] 사용자 서비스 호출 — Timeout(2s) + CircuitBreaker 적용.
        // 사용자 등급 정보는 배송비/할인 계산에 필수이므로 폴백 없이 예외를 전파한다.
        User user = resilientCallExecutor.execute("userService",
                () -> userService.findById(userId));
        BigDecimal totalPrice = cartService.calculateTotal(items);
        BigDecimal estimatedShippingFee = shippingFeeCalculator.calculateShippingFee(
                user.getTier(), totalPrice);
        BigDecimal estimatedFinalAmount = shippingFeeCalculator.calculateFinalAmount(
                totalPrice, BigDecimal.ZERO, estimatedShippingFee);

        // [Resilience4j] 쿠폰 서비스 호출 — Timeout(3s) + CircuitBreaker + 폴백 적용.
        // 쿠폰 목록은 비필수(non-critical) 데이터이므로, 장애 시 빈 목록으로 폴백하여
        // 체크아웃 페이지를 쿠폰 없이 정상 표시할 수 있다.
        // 이를 통해 쿠폰 서비스 장애가 전체 체크아웃 플로우를 중단시키지 않는다.
        List<UserCoupon> availableCoupons = resilientCallExecutor.executeWithFallback(
                "couponService",
                () -> couponService.getAvailableCoupons(userId),
                ex -> {
                    log.warn("[CheckoutPreview] 쿠폰 서비스 장애 — 쿠폰 없이 체크아웃 진행. userId={}, error={}",
                            userId, ex.getMessage());
                    return Collections.emptyList();
                });
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
