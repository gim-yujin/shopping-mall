package com.shop.domain.order.service;

import com.shop.domain.cart.entity.Cart;
import com.shop.domain.cart.repository.CartRepository;
import com.shop.domain.coupon.entity.UserCoupon;
import com.shop.domain.coupon.repository.UserCouponRepository;
import com.shop.domain.inventory.entity.ProductInventoryHistory;
import com.shop.domain.inventory.repository.ProductInventoryHistoryRepository;
import com.shop.domain.order.entity.Order;
import com.shop.domain.point.entity.PointHistory;
import com.shop.domain.point.repository.PointHistoryRepository;
import com.shop.domain.user.entity.User;
import com.shop.global.event.OrderCompletedEvent;
import com.shop.global.exception.BusinessException;
import com.shop.global.outbox.OutboxEventPublisher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
class OrderPostProcessor {

    private final ProductInventoryHistoryRepository inventoryHistoryRepository;
    private final UserCouponRepository userCouponRepository;
    private final PointHistoryRepository pointHistoryRepository;
    private final CartRepository cartRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final ApplicationEventPublisher applicationEventPublisher;

    OrderPostProcessor(ProductInventoryHistoryRepository inventoryHistoryRepository,
                       UserCouponRepository userCouponRepository,
                       PointHistoryRepository pointHistoryRepository,
                       CartRepository cartRepository,
                       OutboxEventPublisher outboxEventPublisher,
                       ApplicationEventPublisher applicationEventPublisher) {
        this.inventoryHistoryRepository = inventoryHistoryRepository;
        this.userCouponRepository = userCouponRepository;
        this.pointHistoryRepository = pointHistoryRepository;
        this.cartRepository = cartRepository;
        this.outboxEventPublisher = outboxEventPublisher;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    void finalizeOrder(Order savedOrder,
                       User user,
                       OrderCartSelectionResolver.CartSelection cartSelection,
                       OrderStockProcessor.StockDeductionResult stockResult,
                       UserCoupon userCoupon,
                       int usePoints) {
        Long userId = savedOrder.getUserId();

        List<ProductInventoryHistory> historyEntities = new ArrayList<>(stockResult.inventorySnapshots().size());
        for (OrderStockProcessor.InventorySnapshot snapshot : stockResult.inventorySnapshots()) {
            historyEntities.add(new ProductInventoryHistory(
                    snapshot.productId(), "OUT", snapshot.quantity(),
                    snapshot.beforeStock(), snapshot.afterStock(),
                    "ORDER", savedOrder.getOrderId(), userId
            ));
        }
        inventoryHistoryRepository.saveAll(historyEntities);

        if (userCoupon != null) {
            int updatedRows = userCouponRepository.markAsUsedIfUnused(
                    userCoupon.getUserCouponId(),
                    savedOrder.getOrderId(),
                    LocalDateTime.now()
            );
            if (updatedRows != 1) {
                throw new BusinessException("COUPON_ALREADY_USED", "이미 사용된 쿠폰입니다.");
            }
        }

        user.addTotalSpent(savedOrder.getFinalAmount());

        if (usePoints > 0) {
            pointHistoryRepository.save(new PointHistory(
                    userId, PointHistory.USE, usePoints, user.getPointBalance(),
                    "ORDER", savedOrder.getOrderId(),
                    "주문 사용 (주문번호: " + savedOrder.getOrderNumber() + ")"
            ));
        }

        List<Long> productIds = stockResult.orderLines().stream()
                .map(OrderStockProcessor.OrderLine::productId)
                .toList();
        applicationEventPublisher.publishEvent(new OrderCompletedEvent(
                savedOrder.getOrderId(), userId, savedOrder.getFinalAmount(), productIds));

        if (cartSelection.isPartialOrder()) {
            List<Long> orderedCartIds = cartSelection.items().stream().map(Cart::getCartId).toList();
            cartRepository.deleteAllById(orderedCartIds);
        } else {
            cartRepository.deleteByUserId(userId);
        }

        outboxEventPublisher.publishStockChanged(productIds);
        outboxEventPublisher.publishOrderCreated(
                savedOrder.getOrderId(), userId, savedOrder.getFinalAmount());
    }
}
