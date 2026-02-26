package com.shop.domain.order.service;

import com.shop.domain.cart.entity.Cart;
import com.shop.domain.cart.repository.CartRepository;
import com.shop.domain.coupon.entity.UserCoupon;
import com.shop.domain.coupon.repository.UserCouponRepository;
import com.shop.domain.inventory.entity.ProductInventoryHistory;
import com.shop.domain.inventory.repository.ProductInventoryHistoryRepository;
import com.shop.domain.order.dto.OrderCreateRequest;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.entity.OrderItem;
import com.shop.domain.order.repository.OrderRepository;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.entity.UserTier;
import com.shop.domain.user.repository.UserRepository;
import com.shop.domain.user.repository.UserTierRepository;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.InsufficientStockException;
import com.shop.global.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final CartRepository cartRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final ProductInventoryHistoryRepository inventoryHistoryRepository;
    private final UserCouponRepository userCouponRepository;
    private final UserTierRepository userTierRepository;
    private final EntityManager entityManager;

    public OrderService(OrderRepository orderRepository, CartRepository cartRepository,
                        ProductRepository productRepository, UserRepository userRepository,
                        ProductInventoryHistoryRepository inventoryHistoryRepository,
                        UserCouponRepository userCouponRepository,
                        UserTierRepository userTierRepository,
                        EntityManager entityManager) {
        this.orderRepository = orderRepository;
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.inventoryHistoryRepository = inventoryHistoryRepository;
        this.userCouponRepository = userCouponRepository;
        this.userTierRepository = userTierRepository;
        this.entityManager = entityManager;
    }

    private static final BigDecimal SHIPPING_FEE_BASE = new BigDecimal("3000");

    public BigDecimal calculateShippingFee(UserTier tier, BigDecimal itemTotalAmount) {
        BigDecimal freeThreshold = tier.getFreeShippingThreshold();
        if (freeThreshold.compareTo(BigDecimal.ZERO) == 0 || itemTotalAmount.compareTo(freeThreshold) >= 0) {
            return BigDecimal.ZERO;
        }
        return SHIPPING_FEE_BASE;
    }

    public BigDecimal calculateFinalAmount(BigDecimal itemTotalAmount, BigDecimal totalDiscount, BigDecimal shippingFee) {
        BigDecimal finalAmount = itemTotalAmount.subtract(totalDiscount).add(shippingFee);
        if (finalAmount.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        return finalAmount;
    }

    @Transactional
    public Order createOrder(Long userId, OrderCreateRequest request) {
        List<Cart> cartItems = cartRepository.findByUserIdWithProduct(userId);
        if (cartItems.isEmpty()) {
            throw new BusinessException("EMPTY_CART", "장바구니가 비어있습니다.");
        }
        // 데드락 예방을 위해 상품 ID 순으로 정렬 (자원 획득 순서 일관성 유지)
        cartItems.sort(java.util.Comparator.comparing(cart -> cart.getProduct().getProductId()));

        // 0) 사용자 & 등급 정보 로드
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("사용자", userId));
        UserTier tier = user.getTier();
        BigDecimal tierDiscountRate = tier.getDiscountRate();  // e.g. 5.00 = 5%

        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal tierDiscountTotal = BigDecimal.ZERO;
        String orderNumber = generateOrderNumber();
        List<OrderLine> orderLines = new ArrayList<>();

        // 1) 재고 차감 & 주문 금액 계산
        for (Cart cart : cartItems) {
            Product product = productRepository.findByIdWithLock(cart.getProduct().getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("상품", cart.getProduct().getProductId()));

            // JOIN FETCH로 L1 캐시에 로드된 낡은 상태를 DB 최신값으로 갱신
            // PESSIMISTIC_WRITE 락을 잡은 상태이므로 다른 트랜잭션이 변경할 수 없음
            entityManager.refresh(product);

            if (product.getStockQuantity() < cart.getQuantity()) {
                throw new InsufficientStockException(product.getProductName(),
                        cart.getQuantity(), product.getStockQuantity());
            }

            int beforeStock = product.getStockQuantity();
            product.decreaseStock(cart.getQuantity());

            BigDecimal subtotal = product.getPrice().multiply(BigDecimal.valueOf(cart.getQuantity()));
            totalAmount = totalAmount.add(subtotal);

            orderLines.add(new OrderLine(
                    product.getProductId(),
                    product.getProductName(),
                    cart.getQuantity(),
                    product.getPrice(),
                    subtotal
            ));

            // 등급 할인 계산 (아이템별)
            BigDecimal itemTierDiscount = subtotal.multiply(tierDiscountRate)
                    .divide(BigDecimal.valueOf(100), 0, java.math.RoundingMode.FLOOR);
            tierDiscountTotal = tierDiscountTotal.add(itemTierDiscount);

            inventoryHistoryRepository.save(new ProductInventoryHistory(
                    product.getProductId(), "OUT", cart.getQuantity(),
                    beforeStock, product.getStockQuantity(),
                    "ORDER", null, userId
            ));
        }

        // 2) 쿠폰 할인 적용 (상품 금액 기준)
        BigDecimal couponDiscount = BigDecimal.ZERO;
        UserCoupon userCoupon = null;
        if (request.userCouponId() != null) {
            userCoupon = userCouponRepository.findByIdWithLock(request.userCouponId())
                    .orElseThrow(() -> new BusinessException("COUPON_NOT_FOUND", "쿠폰을 찾을 수 없습니다."));

            if (!userCoupon.getUserId().equals(userId)) {
                throw new BusinessException("COUPON_INVALID", "본인의 쿠폰만 사용할 수 있습니다.");
            }
            if (!userCoupon.isAvailable()) {
                throw new BusinessException("COUPON_EXPIRED", "사용할 수 없는 쿠폰입니다.");
            }

            // 쿠폰 최소 주문 기준은 "상품 금액(등급 할인/쿠폰 할인 전)" 기준으로 적용한다.
            couponDiscount = userCoupon.getCoupon().calculateDiscount(totalAmount);
        }

        BigDecimal totalDiscount = tierDiscountTotal.add(couponDiscount);

        // 3) 배송비 계산 (등급별 무료배송 기준)
        BigDecimal shippingFee = calculateShippingFee(tier, totalAmount);

        // 4) 최종 금액 & 주문 생성
        BigDecimal finalAmount = calculateFinalAmount(totalAmount, totalDiscount, shippingFee);

        BigDecimal pointRateSnapshot = tier.getPointEarnRate(); // e.g. 1.50 = 1.5%
        int earnedPointsSnapshot = finalAmount.multiply(pointRateSnapshot)
                .divide(BigDecimal.valueOf(100), 0, java.math.RoundingMode.FLOOR).intValue();

        Order order = new Order(orderNumber, userId, totalAmount, totalDiscount,
                shippingFee, finalAmount, pointRateSnapshot, earnedPointsSnapshot,
                request.paymentMethod(), request.shippingAddress(),
                request.recipientName(), request.recipientPhone());

        for (OrderLine orderLine : orderLines) {
            OrderItem item = new OrderItem(orderLine.productId(), orderLine.productName(),
                    orderLine.quantity(), orderLine.unitPrice(), tierDiscountRate, orderLine.subtotal());
            order.addItem(item);
        }

        order.markPaid();
        Order savedOrder = orderRepository.save(order);

        // 5) 쿠폰 사용 처리 (DB 레벨 원자적 전환 보장)
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

        // 6) 포인트 적립 (등급별 적립률 적용)
        user.addTotalSpent(finalAmount);
        user.addPoints(earnedPointsSnapshot);

        // 7) 등급 재계산
        userTierRepository.findFirstByMinSpentLessThanEqualOrderByTierLevelDesc(user.getTotalSpent())
                .ifPresent(user::updateTier);

        cartRepository.deleteByUserId(userId);
        return savedOrder;
    }

    public Page<Order> getOrdersByUser(Long userId, Pageable pageable) {
        Page<Order> orders = orderRepository.findByUserId(userId, pageable);
        // OSIV off: 트랜잭션 내에서 Lazy 컬렉션 초기화 (batch_fetch_size=100 활용)
        orders.getContent().forEach(order -> order.getItems().size());
        return orders;
    }

    public Order getOrderDetail(Long orderId, Long userId) {
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("주문", orderId));
        order.getItems().size(); // OSIV off: Lazy 컬렉션 초기화
        return order;
    }

    @Transactional
    public void cancelOrder(Long orderId, Long userId) {
        // 이중 취소 방지를 위해 Order에 비관적 락 적용
        Order order = orderRepository.findByIdAndUserIdWithLock(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("주문", orderId));
        cancelOrderInternal(order, userId);
    }

    public Page<Order> getAllOrders(Pageable pageable) {
        Page<Order> orders = orderRepository.findAllByOrderByOrderDateDesc(pageable);
        orders.getContent().forEach(order -> order.getItems().size());
        return orders;
    }

    public Page<Order> getOrdersByStatus(String status, Pageable pageable) {
        Page<Order> orders = orderRepository.findByStatus(status, pageable);
        orders.getContent().forEach(order -> order.getItems().size());
        return orders;
    }

    @Transactional
    public void updateOrderStatus(Long orderId, String status) {
        Order order = orderRepository.findByIdWithLock(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("주문", orderId));

        Set<String> supportedStatuses = Set.of("PENDING", "PAID", "SHIPPED", "DELIVERED", "CANCELLED");
        if (!supportedStatuses.contains(status)) {
            throw new BusinessException("INVALID_STATUS", "잘못된 주문 상태입니다.");
        }

        String currentStatus = order.getOrderStatus();
        Map<String, Set<String>> allowedTransitions = Map.of(
                "PENDING", Set.of("PENDING", "PAID", "CANCELLED"),
                "PAID", Set.of("PAID", "SHIPPED", "CANCELLED"),
                "SHIPPED", Set.of("SHIPPED", "DELIVERED"),
                "DELIVERED", Set.of("DELIVERED"),
                "CANCELLED", Set.of("CANCELLED")
        );

        Set<String> nextStatuses = allowedTransitions.get(currentStatus);
        if (nextStatuses == null || !nextStatuses.contains(status)) {
            throw new BusinessException(
                    "INVALID_STATUS_TRANSITION",
                    "허용되지 않는 주문 상태 전이입니다. [" + currentStatus + " -> " + status + "]"
            );
        }

        if ("CANCELLED".equals(status) && !order.isCancellable()) {
            throw new BusinessException("INVALID_STATUS_TRANSITION",
                    "취소할 수 없는 주문 상태입니다. [" + currentStatus + " -> " + status + "]");
        }

        switch (status) {
            case "PAID" -> order.markPaid();
            case "SHIPPED" -> order.markShipped();
            case "DELIVERED" -> order.markDelivered();
            case "CANCELLED" -> cancelOrderInternal(order, order.getUserId());
            default -> throw new BusinessException("INVALID_STATUS", "잘못된 주문 상태입니다.");
        }
    }

    private void cancelOrderInternal(Order order, Long userId) {
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
                product.increaseStock(item.getQuantity());
                inventoryHistoryRepository.save(new ProductInventoryHistory(
                        product.getProductId(), "IN", item.getQuantity(),
                        before, product.getStockQuantity(), "RETURN", orderId, userId));
            }
        }

        // 2) 누적금액 & 포인트 차감 & 등급 재계산
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("사용자", userId));
        BigDecimal finalAmount = order.getFinalAmount();
        user.addTotalSpent(finalAmount.negate());

        user.addPoints(-order.getEarnedPointsSnapshot());

        userTierRepository.findFirstByMinSpentLessThanEqualOrderByTierLevelDesc(user.getTotalSpent())
                .ifPresent(user::updateTier);

        // 3) 쿠폰 복원
        userCouponRepository.findByOrderId(orderId).ifPresent(UserCoupon::cancelUse);

        order.cancel();
    }

    private String generateOrderNumber() {
        String datePart = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        String randomPart = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        return datePart + "-" + randomPart;
    }

    // 주문 생성 중 계산된 상품별 스냅샷 데이터를 임시로 보관하는 내부 DTO
    private record OrderLine(Long productId, String productName, int quantity,
                             BigDecimal unitPrice, BigDecimal subtotal) {
    }
}
