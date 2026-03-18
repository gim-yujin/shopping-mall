package com.shop.global.event;

import java.math.BigDecimal;
import java.util.List;

/**
 * [Phase 6] 주문 취소/부분 취소 도메인 이벤트.
 *
 * <p><b>문제:</b> OrderCancellationService, PartialCancellationService에서
 * 등급 재계산이 취소 트랜잭션 안에서 동기 실행된다.
 * 취소 트랜잭션은 이미 Product(재고 복구) + User(포인트 환불) 락을 보유하고 있어,
 * 추가적인 UserTier 조회가 트랜잭션 시간을 더 연장한다.</p>
 *
 * <p><b>해결:</b> 주문 생성과 동일하게 트랜잭션 커밋 후 비동기로 등급을 재계산한다.
 * total_spent 차감은 취소 트랜잭션 안에서 완료되므로 비동기 핸들러는
 * 최신 total_spent 기준으로 정확한 등급을 계산할 수 있다.</p>
 *
 * @param orderId        주문 ID
 * @param userId         사용자 ID (등급 재계산 대상)
 * @param refundedAmount 환불 금액 (로깅용)
 * @param productIds     취소된 상품 ID 목록 (알림용)
 */
public record OrderCancelledEvent(
        Long orderId,
        Long userId,
        BigDecimal refundedAmount,
        List<Long> productIds
) {
}
