package com.shop.domain.flashsale.service;

import com.shop.domain.flashsale.dto.FlashSalePurchaseResponse;
import com.shop.domain.flashsale.entity.FlashSale;
import com.shop.domain.flashsale.entity.FlashSaleItem;
import com.shop.domain.flashsale.entity.FlashSalePurchase;
import com.shop.domain.flashsale.exception.DuplicateFlashSalePurchaseException;
import com.shop.domain.flashsale.exception.FlashSaleSoldOutException;
import com.shop.domain.flashsale.exception.FlashSaleWindowClosedException;
import com.shop.domain.flashsale.repository.FlashSaleItemRepository;
import com.shop.domain.flashsale.repository.FlashSalePurchaseRepository;
import com.shop.domain.order.entity.Order;
import com.shop.global.exception.ResourceNotFoundException;
import com.shop.global.redis.StockKeyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;

/**
 * v2 활성화 시 {@link FlashSaleCommandService} 를 치환한다 — 재고 카운터를 PostgreSQL
 * 의 {@code remaining_quantity} 가 아닌 Redis 키 {@code fs:item:{id}} 로 처리한다.
 *
 * <p>{@link FlashSaleCommandService#purchase} 만 오버라이드한다. private 멤버에 접근할
 * 수 없으므로 본 서브클래스는 itemRepo / purchaseRepo / orderFactory 를 자체 필드로
 * 다시 주입받는다. super 생성자에는 {@code "cas"} 를 넘겨 부모의 lockStrategy 검증을
 * 통과시키되, 부모의 reserve* 메서드는 호출하지 않는다.</p>
 */
public class RedisFlashSaleCommandService extends FlashSaleCommandService {

    private static final Logger log = LoggerFactory.getLogger(RedisFlashSaleCommandService.class);
    private static final int FIXED_QUANTITY = 1;
    private static final long LUA_INSUFFICIENT = -1L;
    private static final long LUA_KEY_MISSING = -2L;

    private final FlashSaleItemRepository itemRepository;
    private final FlashSalePurchaseRepository purchaseRepository;
    private final FlashSaleOrderFactory orderFactory;
    private final StringRedisTemplate redis;
    private final RedisScript<Long> stockDecrementScript;
    private final StockKeyResolver keys;

    public RedisFlashSaleCommandService(FlashSaleItemRepository itemRepository,
                                        FlashSalePurchaseRepository purchaseRepository,
                                        FlashSaleOrderFactory orderFactory,
                                        StringRedisTemplate redis,
                                        RedisScript<Long> stockDecrementScript,
                                        StockKeyResolver keys) {
        super(itemRepository, purchaseRepository, orderFactory, STRATEGY_CAS);
        this.itemRepository = itemRepository;
        this.purchaseRepository = purchaseRepository;
        this.orderFactory = orderFactory;
        this.redis = redis;
        this.stockDecrementScript = stockDecrementScript;
        this.keys = keys;
        log.info("event=flash_sale_lock_strategy strategy=redis");
    }

    @Override
    @Transactional
    public FlashSalePurchaseResponse purchase(Long flashSaleId, Long flashSaleItemId, Long userId) {
        FlashSaleItem item = itemRepository.findByItemAndSale(flashSaleItemId, flashSaleId)
                .orElseThrow(() -> new ResourceNotFoundException("플래시 세일 상품", flashSaleItemId));

        FlashSale sale = item.getFlashSale();
        if (!sale.isOpenAt(LocalDateTime.now())) {
            throw new FlashSaleWindowClosedException(flashSaleId);
        }

        Long ret = redis.execute(stockDecrementScript,
                Collections.singletonList(keys.flashSaleItemKey(flashSaleItemId)),
                Integer.toString(FIXED_QUANTITY));
        if (ret == null || ret == LUA_INSUFFICIENT || ret == LUA_KEY_MISSING) {
            log.info("event=flash_sale_reserve_soldout sale_id={} item_id={} user_id={} strategy=redis lua_ret={}",
                    flashSaleId, flashSaleItemId, userId, ret);
            throw new FlashSaleSoldOutException(flashSaleItemId);
        }

        try {
            Order order = orderFactory.create(userId, item, FIXED_QUANTITY);
            purchaseRepository.save(FlashSalePurchase.record(
                    flashSaleId, flashSaleItemId, userId, order.getOrderId()));
            purchaseRepository.flush();
            log.info("event=flash_sale_reserve_success sale_id={} item_id={} user_id={} order_id={} strategy=redis remaining={}",
                    flashSaleId, flashSaleItemId, userId, order.getOrderId(), ret);
            return FlashSalePurchaseResponse.of(order, item, FIXED_QUANTITY);
        } catch (DataIntegrityViolationException e) {
            // 1-per-user UNIQUE 위반 — Redis 카운터 보상 (DB 트랜잭션은 롤백되지만 Redis 는 별도 시스템)
            redis.opsForValue().increment(keys.flashSaleItemKey(flashSaleItemId), FIXED_QUANTITY);
            log.info("event=flash_sale_purchase_duplicate sale_id={} user_id={} strategy=redis (redis 보상 수행)",
                    flashSaleId, userId);
            throw new DuplicateFlashSalePurchaseException(flashSaleId, userId);
        }
    }
}
