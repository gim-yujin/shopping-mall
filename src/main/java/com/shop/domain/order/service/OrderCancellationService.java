package com.shop.domain.order.service;

import com.shop.domain.coupon.entity.UserCoupon;
import com.shop.domain.coupon.repository.UserCouponRepository;
import com.shop.domain.inventory.entity.ProductInventoryHistory;
import com.shop.domain.inventory.repository.ProductInventoryHistoryRepository;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.entity.OrderItem;
import com.shop.domain.order.repository.OrderRepository;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.repository.UserRepository;
import com.shop.domain.user.repository.UserTierRepository;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import com.shop.domain.product.service.ProductCacheEvictHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 주문 취소 전담 서비스.
 *
 * OrderService(God Class)에서 분리: 재고 복구, 포인트 환불, 쿠폰 복원,
 * 등급 재계산 등 주문 취소에 필요한 모든 보상(compensating) 로직을 담당한다.
 */
@Service
public class OrderCancellationService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final ProductInventoryHistoryRepository inventoryHistoryRepository;
    private final UserCouponRepository userCouponRepository;
    private final UserTierRepository userTierRepository;
    private final EntityManager entityManager;
    // [P1-7] 캐시 무효화 로직을 공유 헬퍼로 위임 (기존 CacheManager 직접 사용에서 전환)
    private final ProductCacheEvictHelper productCacheEvictHelper;

    public OrderCancellationService(OrderRepository orderRepository,
                                     ProductRepository productRepository,
                                     UserRepository userRepository,
                                     ProductInventoryHistoryRepository inventoryHistoryRepository,
                                     UserCouponRepository userCouponRepository,
                                     UserTierRepository userTierRepository,
                                     EntityManager entityManager,
                                     ProductCacheEvictHelper productCacheEvictHelper) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.inventoryHistoryRepository = inventoryHistoryRepository;
        this.userCouponRepository = userCouponRepository;
        this.userTierRepository = userTierRepository;
        this.entityManager = entityManager;
        this.productCacheEvictHelper = productCacheEvictHelper;
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
     * 2) 누적금액 차감 & 포인트 환불 & 등급 재계산
     * 3) 쿠폰 복원
     * 4) 주문 상태 CANCELLED로 변경
     * 5) 상품 상세 캐시 무효화
     */
    @Transactional
    public void cancelOrderInternal(Order order, Long userId) {
        if (!order.isCancellable()) {
            throw new BusinessException("CANCEL_FAIL", "취소할 수 없는 주문 상태입니다.");
        }

        Long orderId = order.getOrderId();

        // 1) 재고 복구 — 데드락 예방을 위해 상품 ID 순으로 정렬
        List<OrderItem> sortedItems = order.getItems().stream()
                .sorted(java.util.Comparator.comparing(OrderItem::getProductId))
                .toList();

        for (OrderItem item : sortedItems) {
            Product product = productRepository.findByIdWithLock(item.getProductId())
                    .orElse(null);
            if (product != null) {
                entityManager.refresh(product);
                int before = product.getStockQuantity();
                product.increaseStockAndRollbackSales(item.getQuantity());
                inventoryHistoryRepository.save(new ProductInventoryHistory(
                        product.getProductId(), "IN", item.getQuantity(),
                        before, product.getStockQuantity(), "RETURN", orderId, userId));
            }
        }

        // 2) 누적금액 & 포인트 차감 & 사용 포인트 환불 & 등급 재계산
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("사용자", userId));
        BigDecimal finalAmount = order.getFinalAmount();
        user.addTotalSpent(finalAmount.negate());

        // [BUG FIX] 포인트 적립 차감과 사용 환불을 net 값으로 한 번에 처리.
        // 이전: addPoints(-earned) → addPoints(+used) 순차 호출 시,
        //   addPoints 내부의 음수→0 클램핑이 중간 단계에서 발생하여
        //   예) 잔액 100P, 적립 500P, 사용 300P → 100-500=→0 → 0+300=300P (오류)
        //   올바른 결과: 100 - 500 + 300 = -100 → 0P
        // 이후: net = usedPoints - earnedPoints 를 한 번에 addPoints 호출하여
        //   중간 클램핑으로 인한 부당 지급을 방지한다.
        int netPointChange = order.getUsedPoints() - order.getEarnedPointsSnapshot();
        user.addPoints(netPointChange);

        userTierRepository.findFirstByMinSpentLessThanEqualOrderByTierLevelDesc(user.getTotalSpent())
                .ifPresent(user::updateTier);

        // 3) 쿠폰 복원
        userCouponRepository.findByOrderId(orderId).ifPresent(UserCoupon::cancelUse);

        order.cancel();

        // 4) 재고가 변경된 상품의 상세 캐시 무효화
        // [P1-7] 공유 헬퍼로 위임 (기존 private 중복 메서드 제거)
        productCacheEvictHelper.evictProductDetailCaches(sortedItems.stream().map(OrderItem::getProductId).toList());
    }
}
