package com.shop.domain.order.service;

import com.shop.domain.inventory.entity.ProductInventoryHistory;
import com.shop.domain.inventory.repository.ProductInventoryHistoryRepository;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.entity.OrderItem;
import com.shop.domain.order.entity.OrderStatus;
import com.shop.domain.order.repository.OrderItemRepository;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.repository.UserRepository;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class PartialCancellationService {

    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final ProductInventoryHistoryRepository inventoryHistoryRepository;
    private final UserRepository userRepository;

    public PartialCancellationService(OrderItemRepository orderItemRepository,
                                      ProductRepository productRepository,
                                      ProductInventoryHistoryRepository inventoryHistoryRepository,
                                      UserRepository userRepository) {
        this.orderItemRepository = orderItemRepository;
        this.productRepository = productRepository;
        this.inventoryHistoryRepository = inventoryHistoryRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public void partialCancel(Long userId, Long orderId, Long orderItemId, int quantity) {
        OrderItem item = getUserOrderItem(orderItemId, orderId, userId);
        Order order = item.getOrder();
        if (!order.isCancellable()) {
            throw new BusinessException("PARTIAL_CANCEL_NOT_ALLOWED", "부분 취소 가능한 주문 상태가 아닙니다.");
        }

        validateQuantity(item, quantity);
        BigDecimal refundAmount = calculateRefund(item, quantity);
        applyRefund(userId, order, item, quantity, refundAmount, "PARTIAL_CANCEL");
    }

    @Transactional
    public void requestReturn(Long userId, Long orderId, Long orderItemId, int quantity) {
        OrderItem item = getUserOrderItem(orderItemId, orderId, userId);
        Order order = item.getOrder();
        if (order.getOrderStatus() != OrderStatus.DELIVERED) {
            throw new BusinessException("RETURN_NOT_ALLOWED", "반품은 배송완료 상태에서만 가능합니다.");
        }

        validateQuantity(item, quantity);
        BigDecimal refundAmount = calculateRefund(item, quantity);
        applyRefund(userId, order, item, quantity, refundAmount, "RETURN");
    }

    private void applyRefund(Long userId, Order order, OrderItem item, int quantity, BigDecimal refundAmount, String reason) {
        if ("RETURN".equals(reason)) {
            item.applyReturn(quantity, refundAmount);
        } else {
            item.applyPartialCancel(quantity, refundAmount);
        }
        order.addRefundedAmount(refundAmount);

        User user = userRepository.findByIdWithLockAndTier(userId)
                .orElseThrow(() -> new ResourceNotFoundException("사용자", userId));
        user.addTotalSpent(refundAmount.negate());

        Product product = productRepository.findByIdWithLock(item.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("상품", item.getProductId()));
        int before = product.getStockQuantity();
        product.increaseStockAndRollbackSales(quantity);
        inventoryHistoryRepository.save(new ProductInventoryHistory(
                product.getProductId(), "IN", quantity,
                before, product.getStockQuantity(), reason, order.getOrderId(), userId));
    }

    private void validateQuantity(OrderItem item, int quantity) {
        int remaining = item.getRemainingQuantity();
        if (quantity <= 0 || quantity > remaining) {
            throw new BusinessException("INVALID_PARTIAL_QUANTITY", "부분 취소/반품 수량이 유효하지 않습니다.");
        }
    }

    private BigDecimal calculateRefund(OrderItem item, int quantity) {
        BigDecimal unitSubtotal = item.getSubtotal()
                .divide(BigDecimal.valueOf(item.getQuantity()), 2, RoundingMode.HALF_UP);
        return unitSubtotal.multiply(BigDecimal.valueOf(quantity));
    }

    private OrderItem getUserOrderItem(Long orderItemId, Long orderId, Long userId) {
        return orderItemRepository.findByIdAndOrderIdAndUserIdWithLock(orderItemId, orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("주문상품", orderItemId));
    }
}
