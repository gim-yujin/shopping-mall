package com.shop.domain.user.port;

import com.shop.domain.order.dto.OrderListReadModel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 사용자 도메인이 마이페이지에서 주문 데이터를 조회하는 포트.
 *
 * <p>마이페이지 프리뷰에서 사용자의 최근 주문을 조회할 때 사용한다.
 * 실제 구현은 order 도메인의 어댑터가 담당하여 양방향 의존성을 방지한다.</p>
 */
public interface MyPageOrderPort {

    Page<OrderListReadModel> getOrdersByUserFlat(Long userId, Pageable pageable);
}
