package com.shop.domain.order.service;

import com.shop.domain.coupon.entity.UserCoupon;
import com.shop.domain.coupon.repository.UserCouponRepository;
import com.shop.domain.inventory.entity.ProductInventoryHistory;
import com.shop.domain.inventory.repository.ProductInventoryHistoryRepository;
import com.shop.global.outbox.OutboxEventPublisher;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.entity.OrderItem;
import com.shop.domain.order.repository.OrderRepository;
import com.shop.domain.order.validation.OrderInvariantValidator;
import com.shop.domain.point.entity.PointHistory;
import com.shop.domain.point.repository.PointHistoryRepository;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.repository.UserRepository;
import com.shop.global.event.FlashSaleOrderCancelledEvent;
import com.shop.global.event.OrderCancelledEvent;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 주문 취소 전담 서비스.
 *
 * OrderService(God Class)에서 분리: 재고 복구, 포인트 환불, 쿠폰 복원,
 * 등급 재계산 등 주문 취소에 필요한 모든 보상(compensating) 로직을 담당한다.
 * ADR 참고: docs/adr/ADR-0002-point-accrual-on-delivery-and-cancel-policy.md
 */
@Service
public class OrderCancellationService {

    private static final Logger log = LoggerFactory.getLogger(OrderCancellationService.class);

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final ProductInventoryHistoryRepository inventoryHistoryRepository;
    private final UserCouponRepository userCouponRepository;
    private final PointHistoryRepository pointHistoryRepository;
    private final EntityManager entityManager;
    private final OutboxEventPublisher outboxEventPublisher;
    private final OrderInvariantValidator orderInvariantValidator;
    private final ApplicationEventPublisher applicationEventPublisher;

    public OrderCancellationService(OrderRepository orderRepository,
                                     ProductRepository productRepository,
                                     UserRepository userRepository,
                                     ProductInventoryHistoryRepository inventoryHistoryRepository,
                                     UserCouponRepository userCouponRepository,
                                     PointHistoryRepository pointHistoryRepository,
                                     EntityManager entityManager,
                                     OutboxEventPublisher outboxEventPublisher,
                                     OrderInvariantValidator orderInvariantValidator,
                                     ApplicationEventPublisher applicationEventPublisher) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.inventoryHistoryRepository = inventoryHistoryRepository;
        this.userCouponRepository = userCouponRepository;
        this.pointHistoryRepository = pointHistoryRepository;
        this.entityManager = entityManager;
        this.outboxEventPublisher = outboxEventPublisher;
        this.orderInvariantValidator = orderInvariantValidator;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * 사용자 요청에 의한 주문 취소.
     * 이중 취소 방지를 위해 주문에 비관적 락을 적용한다.
     */
    @Transactional
    public void cancelOrder(Long orderId, Long userId) {
        Order order = orderRepository.findByIdAndUserIdWithLock(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("주문", orderId));
        cancelOrderInternal(order, userId);
    }

    /**
     * 주문 취소 내부 로직. 관리자 상태 변경(updateOrderStatus)에서도 호출된다.
     *
     * 처리 순서:
     * 1) 재고 복구 (데드락 예방을 위해 상품 ID 순 정렬)
     * 2) 누적금액(total_spent) 차감 & 포인트 환불 & 등급 재계산
     * 3) 쿠폰 복원
     * 4) 주문 상태 CANCELLED로 변경
     * 5) 상품 상세 캐시 무효화
     */
    @Transactional
    public void cancelOrderInternal(Order order, Long userId) {
        if (!order.isCancellable()) {
            throw new BusinessException("CANCEL_FAIL", "취소할 수 없는 주문 상태입니다.");
        }

        // [Phase 23-5] 플래시 세일 주문은 일반 주문과 보상 경로가 다르다.
        // products.stock_quantity는 차감된 적이 없으므로 복원하지 않는다(인플레 방지).
        // 포인트/쿠폰/티어할인은 모두 0이므로 환불 로직도 우회한다.
        // 잔여 수량 복원과 flash_sale_purchases 행 삭제는 FlashSaleOrderCancelledEvent
        // 동기 리스너(같은 트랜잭션) 가 처리한다.
        if (order.isFlashSaleOrder()) {
            cancelFlashSaleOrder(order);
            return;
        }

        Long orderId = order.getOrderId();

        // 1) 재고 복구 — 데드락 예방을 위해 상품 ID 순으로 정렬
        List<OrderItem> sortedItems = order.getItems().stream()
                .sorted(java.util.Comparator.comparing(OrderItem::getProductId))
                .toList();

        for (OrderItem item : sortedItems) {
            int remainingQuantity = item.getRemainingQuantity();
            if (remainingQuantity <= 0) {
                // 신규 필드(remainingQuantity) 미세팅 레거시/테스트 데이터와의 호환을 위해 수량으로 보정
                remainingQuantity = item.getQuantity();
            }
            if (remainingQuantity <= 0) {
                continue;
            }

            Long productId = item.getProductId();
            Product product = productRepository.findByIdWithLock(productId)
                    .orElseThrow(() -> {
                        log.error("주문 취소 중 상품을 찾을 수 없습니다. orderId={}, productId={}", orderId, productId);
                        return new ResourceNotFoundException("상품", productId);
                    });

            entityManager.refresh(product);
            int before = product.getStockQuantity();
            product.increaseStockAndRollbackSales(remainingQuantity);
            inventoryHistoryRepository.save(new ProductInventoryHistory(
                    product.getProductId(), "IN", remainingQuantity,
                    before, product.getStockQuantity(), "RETURN", orderId, userId));
        }

        // 2) 누적금액(total_spent) 차감 & 포인트 환불 & 등급 재계산
        User user = userRepository.findByIdWithLockAndTier(userId)
                .orElseThrow(() -> new ResourceNotFoundException("사용자", userId));
        BigDecimal refundedAmount = order.getRefundedAmount() == null
                ? BigDecimal.ZERO
                : order.getRefundedAmount();
        BigDecimal remainingRefundAmount = order.getFinalAmount().subtract(refundedAmount);
        if (remainingRefundAmount.compareTo(BigDecimal.ZERO) < 0) {
            remainingRefundAmount = BigDecimal.ZERO;
        }

        // total_spent는 주문 생성/취소 이벤트를 누적 반영한다.
        // 부분 취소가 선행된 경우에는 잔여 환불액만 차감하여 과차감을 방지한다.
        user.addTotalSpent(remainingRefundAmount.negate());
        order.addRefundedAmount(remainingRefundAmount);

        // [P0 FIX] 포인트 적립 이연에 따른 취소 로직 단순화.
        //
        // 기존 문제: 주문 생성 시 즉시 적립된 포인트를 취소 시 차감해야 했다.
        //   net = usedPoints - earnedPoints 를 한 번에 처리했는데,
        //   적립된 포인트를 이미 다른 주문에 사용한 경우 음수 → 0 클램핑으로
        //   포인트 부당 지급이 발생할 수 있었다.
        //
        // 수정: 포인트 적립이 배송 완료(DELIVERED) 시점으로 이연되었으므로,
        //   취소 가능 상태(PENDING, PAID)에서는 적립 포인트가 아직 지급되지 않은 상태이다.
        //   따라서 취소 시에는 사용 포인트만 환불하면 된다.
        //   (isCancellable() 조건에 의해 DELIVERED 이후 취소는 불가능하므로
        //    points_settled=true인 주문이 이 코드에 도달하는 경우는 없다)
        int remainingPointRefund = Math.max(0, order.getUsedPoints() - order.getRefundedPoints());
        if (remainingPointRefund > 0) {
            user.addPoints(remainingPointRefund);
            order.addRefundedPoints(remainingPointRefund);
            pointHistoryRepository.save(new PointHistory(
                    user.getUserId(), PointHistory.REFUND, remainingPointRefund, user.getPointBalance(),
                    "CANCEL", orderId,
                    "주문 취소 환불 (주문번호: " + order.getOrderNumber() + ")"
            ));
        }

        // [Phase 6] 등급 재계산을 비동기 이벤트로 분리.
        // 취소 트랜잭션에서 totalSpent 차감은 이미 완료된 상태이므로,
        // AFTER_COMMIT 비동기 핸들러가 최신 totalSpent로 정확한 등급을 계산한다.
        applicationEventPublisher.publishEvent(new OrderCancelledEvent(
                orderId, userId, remainingRefundAmount,
                sortedItems.stream().map(OrderItem::getProductId).toList()));

        // 3) 쿠폰 복원
        userCouponRepository.findByOrderId(orderId).ifPresent(UserCoupon::cancelUse);

        order.cancel();
        orderInvariantValidator.validateBeforePersist(order);

        // 4) [Outbox] 재고 변경 이벤트를 Outbox 테이블에 기록한다.
        // 비즈니스 데이터(주문 취소)와 같은 트랜잭션에서 원자적으로 저장된다.
        outboxEventPublisher.publishStockChanged(
                sortedItems.stream().map(OrderItem::getProductId).toList()
        );

        // [Phase 6] 주문 취소 이벤트를 Outbox에 기록하여 at-least-once 알림 발송을 보장한다.
        outboxEventPublisher.publishOrderCancelled(orderId, userId, remainingRefundAmount);
    }

    /**
     * [Phase 23-5] 플래시 세일 주문 전용 취소 분기.
     *
     * <p>일반 보상 경로의 다음을 모두 건너뛴다:
     * <ul>
     *   <li>products.stock_quantity 복구 — 플래시 세일은 차감하지 않았으므로 인플레가 됨</li>
     *   <li>total_spent 차감, 포인트 환불, 쿠폰 복원 — 플래시 세일 주문은 모두 0</li>
     *   <li>티어 재계산 이벤트 + outbox stock_changed — 플래시 세일에 무의미</li>
     * </ul>
     * </p>
     *
     * <p>대신 {@link FlashSaleOrderCancelledEvent}를 발행하여 flashsale 도메인의
     * 동기 리스너가 같은 트랜잭션 안에서 remaining_quantity를 +1 복원하고
     * flash_sale_purchases 행을 삭제하도록 위임한다(uk_fsp_user_sale 해제로
     * 같은 사용자가 다시 시도할 수 있게 됨).</p>
     */
    private void cancelFlashSaleOrder(Order order) {
        order.cancel();
        order.addRefundedAmount(order.getFinalAmount());
        orderInvariantValidator.validateBeforePersist(order);
        applicationEventPublisher.publishEvent(new FlashSaleOrderCancelledEvent(order.getOrderId()));
        log.info("event=flash_sale_order_cancelled order_id={} user_id={}",
                order.getOrderId(), order.getUserId());
    }
}
