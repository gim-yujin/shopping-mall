package com.shop.domain.order.service;

import com.shop.domain.order.dto.AdminReturnResponse;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.entity.OrderItem;
import com.shop.domain.order.entity.OrderItemStatus;
import com.shop.domain.order.entity.OrderStatus;
import com.shop.domain.order.repository.OrderItemRepository;
import com.shop.domain.order.repository.OrderRepository;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.repository.UserRepository;
import com.shop.global.exception.ResourceNotFoundException;
import org.hibernate.Hibernate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 주문 조회 전담 서비스.
 *
 * <p>OrderService(God Class)에서 분리: 사용자/관리자 주문 목록 조회,
 * 주문 상세 조회 등 읽기 전용 작업만 담당한다.</p>
 *
 * <h3>[Step 3] 반품 관리 조회 메서드 추가</h3>
 *
 * <p><b>문제:</b> 관리자가 반품 대기 건을 조회하고 처리할 수 있는 서비스 메서드가 없었다.
 * 관리자 대시보드에 반품 대기 건수를 표시할 방법도 없었다.</p>
 *
 * <p><b>해결:</b> 두 가지 메서드를 추가한다.
 * <ul>
 *   <li>{@link #getReturnRequests(Pageable)} — 반품 대기 목록을 AdminReturnResponse DTO로 변환하여 반환</li>
 *   <li>{@link #getPendingReturnCount()} — 반품 대기 건수 카운트 (대시보드 카드용)</li>
 * </ul>
 * </p>
 *
 * <p><b>User 조회 최적화:</b> 반품 목록에서 사용자 이름/이메일을 표시해야 하므로
 * User를 조회해야 한다. N+1을 방지하기 위해 대상 userId를 먼저 수집한 후
 * IN 쿼리로 일괄 조회하여 Map으로 변환한다.</p>
 */
@Service
@Transactional(readOnly = true)
public class OrderQueryService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final UserRepository userRepository;

    public OrderQueryService(OrderRepository orderRepository,
                             OrderItemRepository orderItemRepository,
                             UserRepository userRepository) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.userRepository = userRepository;
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

    // ── Step 3 신규: 반품 관리 조회 ───────────────────────────────

    /**
     * 반품 대기 목록을 관리자용 DTO로 변환하여 반환한다.
     *
     * <p><b>처리 흐름:</b>
     * <ol>
     *   <li>RETURN_REQUESTED 상태의 OrderItem을 페이징 조회 (JOIN FETCH Order)</li>
     *   <li>조회된 아이템들의 userId를 수집하여 User를 일괄 조회 (N+1 방지)</li>
     *   <li>OrderItem + Order + User 정보를 AdminReturnResponse로 매핑</li>
     * </ol>
     * </p>
     *
     * <p><b>User 미존재 방어:</b> 탈퇴 등으로 User가 없는 경우를 대비하여
     * userName/userEmail에 기본값("(탈퇴)", "")을 사용한다.</p>
     */
    public Page<AdminReturnResponse> getReturnRequests(Pageable pageable) {
        Page<OrderItem> items = orderItemRepository.findByStatus(
                OrderItemStatus.RETURN_REQUESTED, pageable);

        // 사용자 정보를 일괄 조회하여 Map으로 변환 (N+1 방지)
        Set<Long> userIds = items.getContent().stream()
                .map(oi -> oi.getOrder().getUserId())
                .collect(Collectors.toSet());

        Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getUserId, Function.identity()));

        return items.map(oi -> {
            Order order = oi.getOrder();
            User user = userMap.get(order.getUserId());
            return new AdminReturnResponse(
                    order.getOrderId(),
                    order.getOrderNumber(),
                    oi.getOrderItemId(),
                    oi.getProductName(),
                    oi.getPendingReturnQuantity(),
                    oi.getReturnReason(),
                    oi.getReturnRequestedAt(),
                    user != null ? user.getUsername() : "(탈퇴)",
                    user != null ? user.getEmail() : ""
            );
        });
    }

    /**
     * 반품 대기 건수를 반환한다 (관리자 대시보드 카드용).
     *
     * <p>partial index {@code idx_order_items_status_return_requested}를 활용하여
     * 전체 order_items를 스캔하지 않고 빠르게 카운트한다.</p>
     */
    public long getPendingReturnCount() {
        return orderItemRepository.countByStatus(OrderItemStatus.RETURN_REQUESTED);
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────

    /**
     * OSIV off 환경에서 Page<Order>의 Lazy 컬렉션(items)을 초기화한다.
     * batch_fetch_size=100이 적용되어 있으므로, 페이지 크기가 100 이하인 한
     * 추가 쿼리 1회로 모든 주문의 아이템이 일괄 로드된다.
     *
     * 참고: Page 쿼리에 JOIN FETCH를 사용하면 Hibernate가 전체 결과를 메모리에
     * 로드한 뒤 페이징하므로(HHH000104 경고), batch fetch가 더 효율적이다.
     *
     * [P1-4] 기존 .size() 호출은 "Lazy 컬렉션 강제 초기화" 의도가 불명확했다.
     * Hibernate.initialize()로 교체하여 의도를 명시적으로 표현한다.
     * 동작은 동일: 프록시 컬렉션에 대해 SELECT를 발행하여 데이터를 로드한다.
     */
    private void initializeOrderItems(Page<Order> orders) {
        orders.getContent().forEach(order -> Hibernate.initialize(order.getItems()));
    }
}
