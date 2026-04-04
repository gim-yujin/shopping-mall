package com.shop.domain.user.dto;

import com.shop.domain.coupon.entity.UserCoupon;
import com.shop.domain.order.dto.OrderListReadModel;
import com.shop.domain.user.entity.User;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * [Phase 25] 마이페이지 프리뷰 데이터.
 *
 * <p>3개의 독립적 서비스 호출 결과를 한 번에 전달한다.
 * {@link com.shop.domain.user.service.MyPagePreviewService}에서
 * StructuredTaskScope로 병렬 조회한 결과를 조합한다.</p>
 *
 * @param user         사용자 정보 (이름, 이메일, 등급, 포인트)
 * @param recentOrders 최근 주문 목록 (CQRS 경량 읽기 모델)
 * @param coupons      사용 가능 쿠폰 목록
 */
public record MyPagePreview(
        User user,
        Page<OrderListReadModel> recentOrders,
        List<UserCoupon> coupons
) {
}
