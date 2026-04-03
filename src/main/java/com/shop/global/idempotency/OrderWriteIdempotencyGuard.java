package com.shop.global.idempotency;

import com.shop.global.exception.BusinessException;
import com.shop.global.metrics.IdempotencyMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 주문 쓰기 요청의 멱등성 키 누락 정책을 관리한다.
 *
 * <p>현재 기본값은 호환 모드(false)이며, 키가 없어도 요청은 통과시키되
 * 로그/메트릭으로 누락 빈도를 관측한다. 이후 클라이언트 전환이 완료되면
 * 설정만으로 강제 모드(true)로 전환할 수 있다.</p>
 */
@Component
public class OrderWriteIdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(OrderWriteIdempotencyGuard.class);

    private final IdempotencyMetrics idempotencyMetrics;
    private final boolean requireOrderWriteKey;

    public OrderWriteIdempotencyGuard(
            IdempotencyMetrics idempotencyMetrics,
            @Value("${app.idempotency.require-order-write-key:false}") boolean requireOrderWriteKey) {
        this.idempotencyMetrics = idempotencyMetrics;
        this.requireOrderWriteKey = requireOrderWriteKey;
    }

    public void handleMissingKey(String channel, String operation, Long userId) {
        idempotencyMetrics.recordMissingKey(channel, operation);
        log.warn("event=idempotency_key_missing scope=order_write channel={} operation={} userId={} enforcement={}",
                channel, operation, userId, requireOrderWriteKey ? "required" : "compatibility");

        if (requireOrderWriteKey) {
            throw new BusinessException(
                    "IDEMPOTENCY_KEY_REQUIRED",
                    "주문 생성/취소/부분 취소 요청에는 멱등성 키가 필요합니다.");
        }
    }
}
