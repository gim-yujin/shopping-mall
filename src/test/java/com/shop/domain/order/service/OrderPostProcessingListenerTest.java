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
 * <p>비동기 이벤트 리스너의 핵심 로직을 검증한다:
 * <ul>
 *   <li>등급 재계산 서비스가 올바른 userId로 호출되는지</li>
 *   <li>알림 서비스가 올바른 파라미터로 호출되는지</li>
 *   <li>하나의 후처리 실패가 다른 후처리를 중단하지 않는지 (실패 격리)</li>
 * </ul></p>
 *
 * <p>@Async 동작(스레드 풀 실행)은 통합 테스트에서 검증한다.
 * 단위 테스트에서는 리스너 메서드를 직접 호출하여 로직만 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class OrderPostProcessingListenerTest {

    @Mock
    private OrderTierRecalculationService tierRecalculationService;

    @Mock
    private OrderNotificationService notificationService;

    @InjectMocks
    private OrderPostProcessingListener listener;

    @Nested
    @DisplayName("주문 생성 후처리")
    class HandleOrderCompleted {

        @Test
        @DisplayName("등급 재계산과 알림 발송이 모두 호출된다")
        void callsTierRecalculationAndNotification() {
            // given
            OrderCompletedEvent event = new OrderCompletedEvent(
                    1L, 100L, new BigDecimal("50000"), List.of(10L, 20L));

            // when
            CompletableFuture<Void> result = listener.handleOrderCompleted(event);

            // then
            verify(tierRecalculationService).recalculateTier(100L);
            verify(notificationService).sendOrderConfirmation(1L, 100L, new BigDecimal("50000"));
            assertThat(result).isCompleted();
        }

        @Test
        @DisplayName("등급 재계산 실패 시에도 알림 발송은 정상 실행된다 (실패 격리)")
        void tierFailureDoesNotBlockNotification() {
            // given: 등급 재계산에서 예외 발생
            doThrow(new RuntimeException("DB 연결 실패"))
                    .when(tierRecalculationService).recalculateTier(anyLong());

            OrderCompletedEvent event = new OrderCompletedEvent(
                    1L, 100L, new BigDecimal("50000"), List.of(10L));

            // when
            CompletableFuture<Void> result = listener.handleOrderCompleted(event);

            // then: 알림은 정상 호출됨
            verify(notificationService).sendOrderConfirmation(1L, 100L, new BigDecimal("50000"));
            assertThat(result).isCompleted();
        }

        @Test
        @DisplayName("알림 발송 실패 시에도 예외가 전파되지 않는다")
        void notificationFailureDoesNotPropagate() {
            // given: 알림 발송에서 예외 발생
            doThrow(new RuntimeException("알림 서버 다운"))
                    .when(notificationService).sendOrderConfirmation(anyLong(), anyLong(), any());

            OrderCompletedEvent event = new OrderCompletedEvent(
                    1L, 100L, new BigDecimal("50000"), List.of(10L));

            // when
            CompletableFuture<Void> result = listener.handleOrderCompleted(event);

            // then: 등급 재계산은 정상 호출되고, 예외가 전파되지 않음
            verify(tierRecalculationService).recalculateTier(100L);
            assertThat(result).isCompleted();
        }
    }

    @Nested
    @DisplayName("주문 취소 후처리")
    class HandleOrderCancelled {

        @Test
        @DisplayName("등급 재계산과 취소 알림이 모두 호출된다")
        void callsTierRecalculationAndCancellationNotice() {
            // given
            OrderCancelledEvent event = new OrderCancelledEvent(
                    2L, 200L, new BigDecimal("30000"), List.of(30L));

            // when
            CompletableFuture<Void> result = listener.handleOrderCancelled(event);

            // then
            verify(tierRecalculationService).recalculateTier(200L);
            verify(notificationService).sendCancellationNotice(2L, 200L, new BigDecimal("30000"));
            assertThat(result).isCompleted();
        }

        @Test
        @DisplayName("등급 재계산 실패 시에도 취소 알림은 정상 실행된다")
        void tierFailureDoesNotBlockCancellationNotice() {
            // given
            doThrow(new RuntimeException("DB 장애"))
                    .when(tierRecalculationService).recalculateTier(anyLong());

            OrderCancelledEvent event = new OrderCancelledEvent(
                    2L, 200L, new BigDecimal("30000"), List.of(30L));

            // when
            CompletableFuture<Void> result = listener.handleOrderCancelled(event);

            // then
            verify(notificationService).sendCancellationNotice(2L, 200L, new BigDecimal("30000"));
            assertThat(result).isCompleted();
        }
    }
}
