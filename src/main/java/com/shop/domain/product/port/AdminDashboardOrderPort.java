package com.shop.domain.product.port;

import com.shop.domain.order.dto.OrderListReadModel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 상품(관리자) 도메인이 주문 데이터를 조회하는 포트.
 *
 * <p>관리자 대시보드 프리뷰에서 주문 목록과 반품 대기 건수를 조회할 때 사용한다.
 * 실제 구현은 order 도메인의 어댑터가 담당하여 양방향 의존성을 방지한다.</p>
 */
public interface AdminDashboardOrderPort {

    Page<OrderListReadModel> getAllOrdersFlat(Pageable pageable);

    long getPendingReturnCount();
}
