package com.shop.domain.order.service;

import com.shop.domain.cart.entity.Cart;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.global.exception.InsufficientStockException;
import com.shop.global.redis.StockKeyResolver;
import jakarta.persistence.EntityManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Redis 기반 재고 차감 — v2 경로의 {@link OrderStockProcessor} 대체.
 *
 * <p>같은 패키지에 두어 package-private 슈퍼클래스 / 내부 record 에 접근한다.
 * {@link OrderStockProcessor} 는 손대지 않으며, v1 모드에서는 본 클래스가 컨텍스트에
 * 등록조차 되지 않으므로 byte-equivalent.</p>
 *
 * <h3>v1 대비 차이</h3>
 * <ul>
 *   <li>{@code SELECT ... FOR UPDATE} 없음 → row lock 큐 미발생</li>
 *   <li>Redis Lua 스크립트로 "현재값 ≥ 차감량 이면 DECRBY" 를 atomic 처리</li>
 *   <li>DB {@code stock_quantity} 미갱신 — Redis 가 단일 진실 공급원 (측정용 단순화,
 *       운영 시 outbox 양방향 동기 필요)</li>
 *   <li>Order line / 할인 / Snapshot 빌드 로직은 v1 과 동일 — Cart 의 Product 엔티티에서
 *       이름·가격을 가져온다</li>
 * </ul>
 */
class RedisOrderStockProcessor extends OrderStockProcessor {

    private static final long LUA_INSUFFICIENT = -1L;
    private static final long LUA_KEY_MISSING = -2L;

    private final StringRedisTemplate redis;
    private final RedisScript<Long> stockDecrementScript;
    private final StockKeyResolver keys;
    private final EntityManager entityManager;

    RedisOrderStockProcessor(ProductRepository productRepository,
                             EntityManager entityManager,
                             StringRedisTemplate redis,
                             RedisScript<Long> stockDecrementScript,
                             StockKeyResolver keys) {
        super(productRepository, entityManager);
        this.redis = redis;
        this.stockDecrementScript = stockDecrementScript;
        this.keys = keys;
        this.entityManager = entityManager;
    }

    @Override
    StockDeductionResult deductStockAndBuildOrderLines(List<Cart> cartItems, BigDecimal tierDiscountRate) {
        // 1차 캐시 우회 — 동일 패턴 (v1 과 동일한 안전성)
        for (Cart cart : cartItems) {
            entityManager.detach(cart.getProduct());
        }

        List<Cart> sorted = new ArrayList<>(cartItems);
        sorted.sort(Comparator.comparingLong(c -> c.getProduct().getProductId()));

        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal tierDiscountTotal = BigDecimal.ZERO;
        List<OrderLine> orderLines = new ArrayList<>(sorted.size());
        List<InventorySnapshot> snapshots = new ArrayList<>(sorted.size());
        List<Cart> applied = new ArrayList<>(sorted.size());

        for (Cart cart : sorted) {
            Product product = cart.getProduct();
            Long productId = product.getProductId();
            int qty = cart.getQuantity();

            Long ret = redis.execute(stockDecrementScript,
                    Collections.singletonList(keys.productKey(productId)),
                    Integer.toString(qty));
            if (ret == null || ret == LUA_INSUFFICIENT || ret == LUA_KEY_MISSING) {
                rollbackIncrement(applied);
                int available = ret != null && ret == LUA_KEY_MISSING ? 0 : -1;
                throw new InsufficientStockException(product.getProductName(), qty, available);
            }
            int afterStock = ret.intValue();
            int beforeStock = afterStock + qty;

            BigDecimal subtotal = product.getPrice().multiply(BigDecimal.valueOf(qty));
            totalAmount = totalAmount.add(subtotal);

            orderLines.add(new OrderLine(productId, product.getProductName(), qty,
                    product.getPrice(), subtotal));

            BigDecimal itemTierDiscount = subtotal.multiply(tierDiscountRate)
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR);
            tierDiscountTotal = tierDiscountTotal.add(itemTierDiscount);

            snapshots.add(new InventorySnapshot(productId, qty, beforeStock, afterStock));
            applied.add(cart);
        }

        return new StockDeductionResult(totalAmount, tierDiscountTotal, orderLines, snapshots);
    }

    private void rollbackIncrement(List<Cart> applied) {
        for (Cart cart : applied) {
            redis.opsForValue().increment(
                    keys.productKey(cart.getProduct().getProductId()), cart.getQuantity());
        }
    }
}
