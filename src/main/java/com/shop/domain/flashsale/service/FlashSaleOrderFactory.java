package com.shop.domain.flashsale.service;

import com.shop.domain.flashsale.entity.FlashSaleItem;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.entity.OrderItem;
import com.shop.domain.order.repository.OrderRepository;
import com.shop.domain.order.validation.OrderInvariantValidator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * [Phase 23-2] 플래시 세일 구매 트랜잭션용 주문 발행기.
 *
 * <p>{@code OrderCreationService.createOrder(userId, OrderCreateRequest)}는
 * 장바구니·쿠폰·포인트·티어 할인·상품 재고 차감(CAS)까지 결합돼 있어 플래시 세일
 * 단건 구매 경로에 재사용이 어렵다. 특히 @Retry/@CircuitBreaker가 걸려 있어
 * 선착순 공정성을 깨는 재시도가 발생할 수 있다.</p>
 *
 * <p>본 팩토리는 단일 {@link FlashSaleItem}로부터 최소 주문(1라인) 을 생성해
 * 저장한다. 호출부({@code FlashSaleCommandService})의 {@code @Transactional}
 * 경계 안에서 동작한다.</p>
 */
@Component
public class FlashSaleOrderFactory {

    private static final String PAYMENT_METHOD_CARD = "CARD";

    private final OrderRepository orderRepository;
    private final OrderInvariantValidator invariantValidator;

    public FlashSaleOrderFactory(OrderRepository orderRepository,
                                 OrderInvariantValidator invariantValidator) {
        this.orderRepository = orderRepository;
        this.invariantValidator = invariantValidator;
    }

    public Order create(Long userId, FlashSaleItem item, int qty) {
        BigDecimal total = item.getSalePrice().multiply(BigDecimal.valueOf(qty));
        Order order = Order.createForFlashSale(generateOrderNumber(), userId, total, PAYMENT_METHOD_CARD);

        OrderItem line = new OrderItem(
                item.getProduct().getProductId(),
                item.getProduct().getProductName(),
                qty,
                item.getSalePrice(),
                BigDecimal.ZERO,
                total);
        order.addItem(line);

        invariantValidator.validateFlashSaleOrder(order);
        return orderRepository.save(order);
    }

    private String generateOrderNumber() {
        String datePart = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        String randomPart = UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12).toUpperCase(Locale.ROOT);
        return datePart + "-" + randomPart;
    }
}
