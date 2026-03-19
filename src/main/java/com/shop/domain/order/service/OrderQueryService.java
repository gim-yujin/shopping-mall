package com.shop.domain.order.service;

import com.shop.domain.order.dto.AdminReturnResponse;
import com.shop.domain.order.dto.OrderListReadModel;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.entity.OrderItem;
import com.shop.domain.order.entity.OrderItemStatus;
import com.shop.domain.order.entity.OrderStatus;
import com.shop.domain.order.repository.OrderItemRepository;
import com.shop.domain.order.repository.OrderRepository;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.repository.UserRepository;
import com.shop.global.exception.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
        fetchOrderItems(orders);
        return orders;
    }

    public Order getOrderDetail(Long orderId, Long userId) {
        return orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("주문", orderId));
    }

    public Page<Order> getAllOrders(Pageable pageable) {
        Page<Order> orders = orderRepository.findAllByOrderByOrderDateDesc(pageable);
        fetchOrderItems(orders);
        return orders;
    }

    public Page<Order> getOrdersByStatus(String status, Pageable pageable) {
        OrderStatus orderStatus = OrderStatus.fromOrThrow(status);
        Page<Order> orders = orderRepository.findByStatus(orderStatus, pageable);
        fetchOrderItems(orders);
        return orders;
    }

    // ── [Phase 18] CQRS 경량 읽기 모델 조회 ────────────────────────────
    //
    // 문제: 기존 주문 목록 조회(getOrdersByUser, getAllOrders, getOrdersByStatus)는
    // Page<Order> 엔티티를 반환한 후 fetchOrderItems()로 2차 쿼리를 발행했다.
    // 목록에 필요한 건 주문 요약과 아이템 수뿐인데, 전체 OrderItem 컬렉션이 로딩되었다.
    //
    // 해결: v_order_list 뷰의 네이티브 쿼리로 단일 쿼리 조회.
    // item_count와 first_product_name을 서브쿼리로 미리 계산하여
    // fetchOrderItems() 2-쿼리 패턴을 제거한다.

    /**
     * [Phase 18] 사용자별 주문 목록 — 경량 읽기 모델 반환.
     * 기존 getOrdersByUser()의 2-쿼리 패턴을 단일 쿼리로 대체한다.
     */
    public Page<OrderListReadModel> getOrdersByUserFlat(Long userId, Pageable pageable) {
        return orderRepository.findByUserIdFlat(userId, pageable)
                .map(OrderListReadModel::fromNativeRow);
    }

    /**
     * [Phase 18] 전체 주문 목록 (관리자) — 경량 읽기 모델 반환.
     * 기존 getAllOrders()의 2-쿼리 패턴을 단일 쿼리로 대체한다.
     */
    public Page<OrderListReadModel> getAllOrdersFlat(Pageable pageable) {
        return orderRepository.findAllOrdersFlat(pageable)
                .map(OrderListReadModel::fromNativeRow);
    }

    /**
     * [Phase 18] 상태별 주문 목록 (관리자) — 경량 읽기 모델 반환.
     * 기존 getOrdersByStatus()의 2-쿼리 패턴을 단일 쿼리로 대체한다.
     */
    public Page<OrderListReadModel> getOrdersByStatusFlat(String status, Pageable pageable) {
        OrderStatus orderStatus = OrderStatus.fromOrThrow(status);
        return orderRepository.findByStatusFlat(orderStatus.name(), pageable)
                .map(OrderListReadModel::fromNativeRow);
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
     * [Phase 2 성능] 2-쿼리 패턴으로 주문 아이템을 명시적으로 일괄 로드한다.
     *
     * <p><b>문제:</b> OSIV off 환경에서 Page&lt;Order&gt;의 Lazy 컬렉션(items)에
     * 트랜잭션 밖에서 접근하면 LazyInitializationException이 발생한다.
     * 기존에는 {@code Hibernate.initialize()} + {@code batch_fetch_size=100}으로
     * 해결했으나, 이 방식은 Hibernate 내부 배치 전략에 암묵적으로 의존하여
     * 실제 발행되는 쿼리 수를 예측하기 어려웠다.</p>
     *
     * <p><b>개선:</b> 페이지 내 주문 ID를 수집한 후
     * {@link OrderRepository#findWithItemsByOrderIds(List)}로 명시적 JOIN FETCH를
     * 수행한다. 이 쿼리는 고정된 IN 절이므로 HHH000104(메모리 페이징) 문제가 없고,
     * 정확히 1회의 추가 쿼리로 모든 아이템이 로드됨을 보장한다.</p>
     *
     * <p><b>동작 원리:</b> 2차 쿼리로 로드된 Order 엔티티는 영속성 컨텍스트에서
     * 1차 쿼리의 Order와 동일 식별자(orderId)로 병합된다. 따라서 반환된 Page의
     * Order 객체에서 {@code getItems()}를 호출하면 추가 쿼리 없이 아이템에 접근된다.</p>
     */
    private void fetchOrderItems(Page<Order> orders) {
        List<Long> orderIds = orders.getContent().stream()
                .map(Order::getOrderId)
                .toList();
        if (!orderIds.isEmpty()) {
            orderRepository.findWithItemsByOrderIds(orderIds);
        }
    }
}
