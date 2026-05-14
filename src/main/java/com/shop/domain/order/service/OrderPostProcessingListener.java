package com.shop.domain.order.service;

import com.shop.global.event.OrderCancelledEvent;
import com.shop.global.event.OrderCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.CompletableFuture;

/**
 * [Phase 6] 주문 후처리 비동기 이벤트 리스너.
 *
 * <p><b>문제:</b> 주문 생성/취소 트랜잭션에서 등급 재계산이 동기 실행되면 두 가지 문제가 발생한다:
 * <ol>
 *   <li><b>응답 지연:</b> 등급 재계산(UserTier 조회)이 주문 응답 시간에 포함된다.</li>
 *   <li><b>실패 전파:</b> 등급 재계산 실패 시 전체 주문 트랜잭션이 롤백되는데,
 *       등급 갱신 실패가 주문 생성 실패로 이어지는 것은 과도한 결합이다.</li>
 * </ol></p>
 *
 * <p><b>해결:</b> {@code @TransactionalEventListener(AFTER_COMMIT)} +
 * {@code @Async("orderPostProcessExecutor")}로 후처리를 분리한다.
 * <ul>
 *   <li>AFTER_COMMIT: 주문 트랜잭션이 성공한 경우에만 후처리가 실행된다.</li>
 *   <li>@Async: 전용 스레드 풀에서 비동기 실행되어 HTTP 응답 스레드를 즉시 해제한다.</li>
 *   <li>등급 재계산 실패 시 TierScheduler가 정기적으로 보정한다.</li>
 * </ul></p>
 *
 * <h3>알림 발송은 이 리스너의 책임이 아니다</h3>
 * <p>{@link com.shop.global.outbox.OutboxEventPublisher} 헤더 주석의 분리 원칙에 따라:
 * <ul>
 *   <li><b>ApplicationEvent(이 리스너):</b> 내부 후처리(등급 재계산) — best-effort,
 *       실패 시 TierScheduler가 보정한다.</li>
 *   <li><b>Outbox 핸들러:</b> 외부 알림(이메일/SMS) — at-least-once 보장이 필요하므로
 *       Outbox 폴러를 통해 발송한다.</li>
 * </ul>
 * 이 리스너가 알림을 직접 발송하면 Outbox 핸들러와 함께 매 주문마다 알림이
 * 2회 이상 발송되므로, 등급 재계산만 담당한다.</p>
 */
@Component
public class OrderPostProcessingListener {

    private static final Logger log = LoggerFactory.getLogger(OrderPostProcessingListener.class);

    private final OrderTierRecalculationService tierRecalculationService;

    public OrderPostProcessingListener(OrderTierRecalculationService tierRecalculationService) {
        this.tierRecalculationService = tierRecalculationService;
    }

    /**
     * 주문 생성 완료 후 등급 재계산.
     *
     * @param event 주문 생성 완료 이벤트 (트랜잭션 커밋 후 전달)
     * @return 비동기 실행 결과 (테스트 검증용)
     */
    @Async("orderPostProcessExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public CompletableFuture<Void> handleOrderCompleted(OrderCompletedEvent event) {
        log.info("주문 후처리 시작 - orderId={}, userId={}", event.orderId(), event.userId());
        try {
            tierRecalculationService.recalculateTier(event.userId());
        } catch (Exception e) {
            log.error("주문 후처리 등급 재계산 실패 - orderId={}, userId={}: {}",
                    event.orderId(), event.userId(), e.getMessage(), e);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 주문 취소 후 등급 재계산.
     *
     * <p>취소 트랜잭션에서 totalSpent 차감은 이미 완료된 상태이므로,
     * 비동기 핸들러는 최신 totalSpent 기준으로 정확한 등급을 재계산한다.</p>
     *
     * @param event 주문 취소 이벤트 (트랜잭션 커밋 후 전달)
     * @return 비동기 실행 결과 (테스트 검증용)
     */
    @Async("orderPostProcessExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public CompletableFuture<Void> handleOrderCancelled(OrderCancelledEvent event) {
        log.info("취소 후처리 시작 - orderId={}, userId={}", event.orderId(), event.userId());
        try {
            tierRecalculationService.recalculateTier(event.userId());
        } catch (Exception e) {
            log.error("취소 후처리 등급 재계산 실패 - orderId={}, userId={}: {}",
                    event.orderId(), event.userId(), e.getMessage(), e);
        }
        return CompletableFuture.completedFuture(null);
    }
}
