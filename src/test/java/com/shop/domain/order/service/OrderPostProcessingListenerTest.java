package com.shop.domain.order.service;

import com.shop.global.event.OrderCancelledEvent;
import com.shop.global.event.OrderCompletedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * [Phase 6] OrderPostProcessingListener 단위 테스트.
 *
 * <p>이 리스너는 등급 재계산만 담당한다(알림 발송은 Outbox 핸들러의 책임).
 * 검증 항목:
 * <ul>
 *   <li>등급 재계산 서비스가 올바른 userId로 호출되는지</li>
 *   <li>등급 재계산 실패가 호출자에게 전파되지 않는지(best-effort)</li>
 * </ul></p>
 *
 * <p>@Async 동작(스레드 풀 실행)은 통합 테스트에서 검증한다.
 * 단위 테스트에서는 리스너 메서드를 직접 호출하여 로직만 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class OrderPostProcessingListenerTest {

    @Mock
    private OrderTierRecalculationService tierRecalculationService;

    @InjectMocks
    private OrderPostProcessingListener listener;

    @Nested
    @DisplayName("주문 생성 후처리")
    class HandleOrderCompleted {

        @Test
        @DisplayName("등급 재계산이 올바른 userId로 호출된다")
        void callsTierRecalculation() {
            OrderCompletedEvent event = new OrderCompletedEvent(
                    1L, 100L, new BigDecimal("50000"), List.of(10L, 20L));

            CompletableFuture<Void> result = listener.handleOrderCompleted(event);

            verify(tierRecalculationService).recalculateTier(100L);
            assertThat(result).isCompleted();
        }

        @Test
        @DisplayName("등급 재계산 실패 시에도 예외가 전파되지 않는다 (best-effort)")
        void tierFailureDoesNotPropagate() {
            doThrow(new RuntimeException("DB 연결 실패"))
                    .when(tierRecalculationService).recalculateTier(anyLong());

            OrderCompletedEvent event = new OrderCompletedEvent(
                    1L, 100L, new BigDecimal("50000"), List.of(10L));

            CompletableFuture<Void> result = listener.handleOrderCompleted(event);

            verify(tierRecalculationService).recalculateTier(100L);
            assertThat(result).isCompleted();
        }
    }

    @Nested
    @DisplayName("주문 취소 후처리")
    class HandleOrderCancelled {

        @Test
        @DisplayName("등급 재계산이 올바른 userId로 호출된다")
        void callsTierRecalculation() {
            OrderCancelledEvent event = new OrderCancelledEvent(
                    2L, 200L, new BigDecimal("30000"), List.of(30L));

            CompletableFuture<Void> result = listener.handleOrderCancelled(event);

            verify(tierRecalculationService).recalculateTier(200L);
            assertThat(result).isCompleted();
        }

        @Test
        @DisplayName("등급 재계산 실패 시에도 예외가 전파되지 않는다 (best-effort)")
        void tierFailureDoesNotPropagate() {
            doThrow(new RuntimeException("DB 장애"))
                    .when(tierRecalculationService).recalculateTier(anyLong());

            OrderCancelledEvent event = new OrderCancelledEvent(
                    2L, 200L, new BigDecimal("30000"), List.of(30L));

            CompletableFuture<Void> result = listener.handleOrderCancelled(event);

            verify(tierRecalculationService).recalculateTier(200L);
            assertThat(result).isCompleted();
        }
    }
}
