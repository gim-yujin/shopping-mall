package com.shop.global.event;

import java.math.BigDecimal;
import java.util.List;

/**
 * [Phase 6] 주문 완료 도메인 이벤트.
 *
 * <p><b>문제:</b> OrderCreationService.finalizeOrder()에서 등급 재계산이
 * 주문 트랜잭션 안에서 동기 실행된다. 등급 재계산은 UserTierRepository 조회 +
 * User 엔티티 갱신이 필요하여, 주문 트랜잭션의 락 보유 시간을 불필요하게 연장한다.
 * 동시 주문이 폭증하면 User 행 락 경합이 주문 응답 지연의 원인이 된다.</p>
 *
 * <p><b>해결:</b> 트랜잭션 커밋 후 비동기 이벤트로 등급 재계산을 분리한다.
 * {@code @TransactionalEventListener(AFTER_COMMIT)}으로 수신하여
 * 주문 트랜잭션 성공이 보장된 시점에서만 후처리를 실행한다.
 * 주문 응답은 등급 재계산 완료를 기다리지 않으므로 응답 시간이 단축된다.</p>
 *
 * @param orderId     주문 ID
 * @param userId      사용자 ID (등급 재계산 대상)
 * @param finalAmount 최종 결제 금액 (로깅용)
 * @param productIds  주문에 포함된 상품 ID 목록 (알림용)
 */
public record OrderCompletedEvent(
        Long orderId,
        Long userId,
        BigDecimal finalAmount,
        List<Long> productIds
) {
}
