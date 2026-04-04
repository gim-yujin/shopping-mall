package com.shop.domain.product.dto;

import com.shop.domain.coupon.dto.CouponStats;
import com.shop.domain.order.dto.OrderListReadModel;
import com.shop.domain.product.entity.Product;
import org.springframework.data.domain.Page;

/**
 * [Phase 25] 관리자 대시보드 프리뷰 데이터.
 *
 * <p>4개의 독립적 서비스 호출 결과를 한 번에 전달한다.
 * {@link com.shop.domain.product.service.AdminDashboardPreviewService}에서
 * StructuredTaskScope로 병렬 조회한 결과를 조합한다.</p>
 *
 * @param products           상품 목록 (관리자용, 비활성 포함)
 * @param recentOrders       최근 주문 목록 (CQRS 경량 읽기 모델)
 * @param couponStats        쿠폰 통계 (전체/활성/발급/사용)
 * @param pendingReturnCount 반품 대기 건수
 */
public record AdminDashboardPreview(
        Page<Product> products,
        Page<OrderListReadModel> recentOrders,
        CouponStats couponStats,
        long pendingReturnCount
) {
}
