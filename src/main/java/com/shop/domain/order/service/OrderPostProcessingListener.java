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
import java.util.concurrent.StructuredTaskScope;

/**
 * [Phase 6] 주문 후처리 비동기 이벤트 리스너.
 *
 * <p><b>문제:</b> 주문 생성/취소 트랜잭션에서 등급 재계산과 알림 발송이
 * 동기 실행되면 두 가지 문제가 발생한다:
 * <ol>
 *   <li><b>응답 지연:</b> 등급 재계산(UserTier 조회)과 알림 발송(외부 연동)이
 *       주문 응답 시간에 포함되어, 사용자가 체감하는 지연이 증가한다.</li>
 *   <li><b>실패 전파:</b> 등급 재계산 실패 시 전체 주문 트랜잭션이 롤백되는데,
 *       등급 갱신 실패가 주문 생성 실패로 이어지는 것은 과도한 결합이다.</li>
 * </ol></p>
 *
 * <p><b>해결:</b> {@code @TransactionalEventListener(AFTER_COMMIT)} +
 * {@code @Async("orderPostProcessExecutor")}로 후처리를 분리한다.
 * <ul>
 *   <li>AFTER_COMMIT: 주문 트랜잭션이 성공한 경우에만 후처리가 실행된다.
 *       롤백 시에는 이벤트가 전달되지 않아 불필요한 처리를 방지한다.</li>
 *   <li>@Async: 전용 스레드 풀(orderPostProcessExecutor)에서 비동기 실행되어
 *       HTTP 응답 스레드를 즉시 해제한다.</li>
 *   <li>CompletableFuture: 비동기 실행 결과를 추적하여 테스트에서 검증할 수 있다.</li>
 * </ul></p>
 *
 * <p><b>실패 격리:</b> 등급 재계산 실패와 알림 발송 실패를 개별 try-catch로 격리한다.
 * 하나의 후처리 단계가 실패해도 다른 단계는 정상 실행된다.
 * 등급 재계산 실패 시 TierScheduler가 정기적으로 보정한다.</p>
 */
@Component
public class OrderPostProcessingListener {

    private static final Logger log = LoggerFactory.getLogger(OrderPostProcessingListener.class);

    private final OrderTierRecalculationService tierRecalculationService;
    private final OrderNotificationService notificationService;

    public OrderPostProcessingListener(OrderTierRecalculationService tierRecalculationService,
                                        OrderNotificationService notificationService) {
        this.tierRecalculationService = tierRecalculationService;
        this.notificationService = notificationService;
    }

    /**
     * 주문 생성 완료 후 비동기 후처리.
     *
     * <p>실행 순서:
     * <ol>
     *   <li>등급 재계산 — 별도 트랜잭션(REQUIRES_NEW)에서 User를 재조회하여 갱신</li>
     *   <li>주문 확인 알림 발송 — 현재 스텁이지만, 실제로는 이메일/SMS 연동</li>
     * </ol></p>
     *
     * @param event 주문 생성 완료 이벤트 (트랜잭션 커밋 후 전달)
     * @return 비동기 실행 결과 (테스트 검증용)
     */
    @Async("orderPostProcessExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @SuppressWarnings("preview")
    public CompletableFuture<Void> handleOrderCompleted(OrderCompletedEvent event) {
        log.info("주문 후처리 시작 - orderId={}, userId={}", event.orderId(), event.userId());

        // [Structured Concurrency] 등급 재계산과 알림 발송을 StructuredTaskScope로 병렬 실행한다.
        //
        // CompletableFuture.runAsync() 대비 개선점:
        // 1. 구조적 수명 보장: scope 종료 시 모든 가상 스레드가 정리되어 누수가 없다.
        //    기존 runAsync()는 ForkJoinPool에 위임하여 스레드 수명 추적이 불가했다.
        // 2. 스레드 계층: 가상 스레드가 scope의 자식으로 추적되어 디버깅이 용이하다.
        // 3. 개별 try-catch로 실패 격리를 유지한다 (scope 관점에서 모든 작업이 성공).
        try (var scope = new StructuredTaskScope<Object>()) {
            scope.fork(() -> {
                try {
                    tierRecalculationService.recalculateTier(event.userId());
                } catch (Exception e) {
                    log.error("주문 후처리 등급 재계산 실패 - orderId={}, userId={}: {}",
                            event.orderId(), event.userId(), e.getMessage(), e);
                }
                return null;
            });

            scope.fork(() -> {
                try {
                    notificationService.sendOrderConfirmation(
                            event.orderId(), event.userId(), event.finalAmount());
                } catch (Exception e) {
                    log.error("주문 후처리 알림 발송 실패 - orderId={}, userId={}: {}",
                            event.orderId(), event.userId(), e.getMessage(), e);
                }
                return null;
            });

            scope.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("주문 후처리 인터럽트 - orderId={}", event.orderId(), e);
        }

        return CompletableFuture.completedFuture(null);
    }

    /**
     * 주문 취소 후 비동기 후처리.
     *
     * <p>취소 트랜잭션에서 totalSpent 차감은 이미 완료된 상태이므로,
     * 비동기 핸들러는 최신 totalSpent 기준으로 정확한 등급을 재계산한다.</p>
     *
     * @param event 주문 취소 이벤트 (트랜잭션 커밋 후 전달)
     * @return 비동기 실행 결과 (테스트 검증용)
     */
    @Async("orderPostProcessExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @SuppressWarnings("preview")
    public CompletableFuture<Void> handleOrderCancelled(OrderCancelledEvent event) {
        log.info("취소 후처리 시작 - orderId={}, userId={}", event.orderId(), event.userId());

        try (var scope = new StructuredTaskScope<Object>()) {
            scope.fork(() -> {
                try {
                    tierRecalculationService.recalculateTier(event.userId());
                } catch (Exception e) {
                    log.error("취소 후처리 등급 재계산 실패 - orderId={}, userId={}: {}",
                            event.orderId(), event.userId(), e.getMessage(), e);
                }
                return null;
            });

            scope.fork(() -> {
                try {
                    notificationService.sendCancellationNotice(
                            event.orderId(), event.userId(), event.refundedAmount());
                } catch (Exception e) {
                    log.error("취소 후처리 알림 발송 실패 - orderId={}, userId={}: {}",
                            event.orderId(), event.userId(), e.getMessage(), e);
                }
                return null;
            });

            scope.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("취소 후처리 인터럽트 - orderId={}", event.orderId(), e);
        }

        return CompletableFuture.completedFuture(null);
    }
}
