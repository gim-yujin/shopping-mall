package com.shop.domain.order.service;

import com.shop.domain.order.entity.Order;
import com.shop.domain.order.entity.OrderStatus;
import com.shop.domain.order.repository.OrderRepository;
import com.shop.global.exception.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 조회 전담 서비스.
 *
 * OrderService(God Class)에서 분리: 사용자/관리자 주문 목록 조회,
 * 주문 상세 조회 등 읽기 전용 작업만 담당한다.
 * 의존성이 OrderRepository 하나뿐이므로 테스트가 간단하다.
 */
@Service
@Transactional(readOnly = true)
public class OrderQueryService {

    private final OrderRepository orderRepository;

    public OrderQueryService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public Page<Order> getOrdersByUser(Long userId, Pageable pageable) {
        Page<Order> orders = orderRepository.findByUserId(userId, pageable);
        initializeOrderItems(orders);
        return orders;
    }

    public Order getOrderDetail(Long orderId, Long userId) {
        return orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("주문", orderId));
    }

    public Page<Order> getAllOrders(Pageable pageable) {
        Page<Order> orders = orderRepository.findAllByOrderByOrderDateDesc(pageable);
        initializeOrderItems(orders);
        return orders;
    }

    public Page<Order> getOrdersByStatus(String status, Pageable pageable) {
        OrderStatus orderStatus = OrderStatus.fromOrThrow(status);
        Page<Order> orders = orderRepository.findByStatus(orderStatus, pageable);
        initializeOrderItems(orders);
        return orders;
    }

    /**
     * OSIV off 환경에서 Page<Order>의 Lazy 컬렉션(items)을 초기화한다.
     * batch_fetch_size=100이 적용되어 있으므로, 페이지 크기가 100 이하인 한
     * 추가 쿼리 1회로 모든 주문의 아이템이 일괄 로드된다.
     *
     * 참고: Page 쿼리에 JOIN FETCH를 사용하면 Hibernate가 전체 결과를 메모리에
     * 로드한 뒤 페이징하므로(HHH000104 경고), batch fetch가 더 효율적이다.
     */
    private void initializeOrderItems(Page<Order> orders) {
        orders.getContent().forEach(order -> order.getItems().size());
    }
}
