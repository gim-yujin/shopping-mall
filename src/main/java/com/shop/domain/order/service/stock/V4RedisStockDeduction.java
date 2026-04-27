package com.shop.domain.order.service.stock;

import com.shop.global.exception.InsufficientStockException;
import com.shop.global.redis.StockKeyResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * V4 — Redis Atomic CAS (Lua 스크립트 기반).
 *
 * <p>{@code SELECT ... FOR UPDATE} 도 {@code @Version} 도 아닌, 메모리 기반 단일 스레드
 * Redis 의 원자성을 활용한다. Lua 스크립트 한 번 호출로 "현재값 ≥ 차감량 이면 DECRBY"
 * 가 atomic 으로 수행된다.</p>
 *
 * <h3>장점</h3>
 * <ul>
 *   <li>DB 행 락 / 영속성 컨텍스트 / JPA 비용 없음</li>
 *   <li>~0.1ms 수준의 단일 RTT — 단일 노드 ~10만 ops/sec</li>
 *   <li>{@code lock_timeout} / {@code connection-timeout} 개념 자체가 사라짐</li>
 * </ul>
 *
 * <h3>단점</h3>
 * <ul>
 *   <li>Redis 가 단일 진실 공급원이 됨 → DB 와의 양방향 동기 정책 별도 필요</li>
 *   <li>Redis 장애 시 폴백 경로 필요 (본 PR 외)</li>
 *   <li>다건 차감의 atomicity 는 본 구현에서 best-effort 보상(Increment 롤백)에 의존</li>
 * </ul>
 *
 * <p>{@code stock.redis.enabled=true} 일 때만 빈으로 등록된다 — 기본 모드(v1)에서는
 * Spring 컨텍스트에 존재하지 않으므로 V1/V2/V3 와 충돌하지 않는다.</p>
 */
@Component
@ConditionalOnProperty(name = "stock.redis.enabled", havingValue = "true")
public class V4RedisStockDeduction implements StockDeductionStrategy {

    private static final long LUA_INSUFFICIENT = -1L;
    private static final long LUA_KEY_MISSING = -2L;

    private final StringRedisTemplate redis;
    private final RedisScript<Long> stockDecrementScript;
    private final StockKeyResolver keys;

    public V4RedisStockDeduction(StringRedisTemplate redis,
                                 RedisScript<Long> stockDecrementScript,
                                 StockKeyResolver keys) {
        this.redis = redis;
        this.stockDecrementScript = stockDecrementScript;
        this.keys = keys;
    }

    @Override
    public List<DeductionResult> deductStock(List<DeductionRequest> items) {
        List<DeductionRequest> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingLong(DeductionRequest::productId));

        List<DeductionResult> results = new ArrayList<>(sorted.size());
        List<DeductionRequest> applied = new ArrayList<>(sorted.size());

        for (DeductionRequest req : sorted) {
            String key = keys.productKey(req.productId());
            Long ret = redis.execute(stockDecrementScript,
                    Collections.singletonList(key),
                    Integer.toString(req.quantity()));
            if (ret == null || ret == LUA_INSUFFICIENT || ret == LUA_KEY_MISSING) {
                rollback(applied);
                throw new InsufficientStockException(
                        "product:" + req.productId(), req.quantity(),
                        ret != null && ret == LUA_KEY_MISSING ? 0 : -1);
            }
            int afterStock = ret.intValue();
            int beforeStock = afterStock + req.quantity();
            results.add(new DeductionResult(req.productId(), beforeStock, afterStock));
            applied.add(req);
        }
        return results;
    }

    /** 다건 중 일부만 차감된 상태에서 실패하면, 이전 성공분을 INCRBY 로 되돌린다. */
    private void rollback(List<DeductionRequest> applied) {
        for (DeductionRequest req : applied) {
            redis.opsForValue().increment(keys.productKey(req.productId()), req.quantity());
        }
    }

    @Override
    public String strategyName() {
        return "V4-Redis";
    }
}
