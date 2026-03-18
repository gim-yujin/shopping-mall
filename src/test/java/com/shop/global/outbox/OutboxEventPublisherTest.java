package com.shop.global.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * OutboxEventPublisher 단위 테스트.
 *
 * <p>publishStockChanged 호출 시 올바른 event_type과 payload로
 * OutboxEvent가 생성되어 Repository에 저장되는지 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class OutboxEventPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private OutboxEventPublisher publisher;

    @Test
    @DisplayName("publishStockChanged: PRODUCT_STOCK_CHANGED 이벤트를 PENDING 상태로 저장한다")
    void publishStockChangedSavesEvent() {
        List<Long> productIds = List.of(1L, 2L, 3L);

        publisher.publishStockChanged(productIds);

        // Repository.save()에 전달된 OutboxEvent를 캡처하여 검증
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());

        OutboxEvent saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo(OutboxEvent.TYPE_PRODUCT_STOCK_CHANGED);
        assertThat(saved.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
        assertThat(saved.getPayload()).contains("\"productIds\"");
        assertThat(saved.getPayload()).contains("1");
        assertThat(saved.getPayload()).contains("2");
        assertThat(saved.getPayload()).contains("3");
        assertThat(saved.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("publishStockChanged: 빈 상품 목록도 정상적으로 저장한다")
    void publishStockChangedWithEmptyList() {
        // 빈 목록이더라도 Outbox 레코드는 생성되어야 한다
        // (폴러에서 빈 목록을 감지하고 로그 경고 후 PROCESSED로 전이)
        publisher.publishStockChanged(List.of());

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());

        OutboxEvent saved = captor.getValue();
        assertThat(saved.getPayload()).contains("\"productIds\":[]");
    }

    /**
     * [Phase 6] publishOrderCreated가 ORDER_CREATED 이벤트를 저장하는지 검증.
     */
    @Test
    @DisplayName("publishOrderCreated: ORDER_CREATED 이벤트를 PENDING 상태로 저장한다")
    void publishOrderCreatedSavesEvent() {
        publisher.publishOrderCreated(1L, 100L, new BigDecimal("50000"));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());

        OutboxEvent saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo(OutboxEvent.TYPE_ORDER_CREATED);
        assertThat(saved.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
        assertThat(saved.getPayload()).contains("\"orderId\"");
        assertThat(saved.getPayload()).contains("\"userId\"");
        assertThat(saved.getPayload()).contains("\"finalAmount\"");
        assertThat(saved.getRetryCount()).isZero();
    }

    /**
     * [Phase 6] publishOrderCancelled가 ORDER_CANCELLED 이벤트를 저장하는지 검증.
     */
    @Test
    @DisplayName("publishOrderCancelled: ORDER_CANCELLED 이벤트를 PENDING 상태로 저장한다")
    void publishOrderCancelledSavesEvent() {
        publisher.publishOrderCancelled(2L, 200L, new BigDecimal("30000"));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());

        OutboxEvent saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo(OutboxEvent.TYPE_ORDER_CANCELLED);
        assertThat(saved.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
        assertThat(saved.getPayload()).contains("\"orderId\"");
        assertThat(saved.getPayload()).contains("\"refundedAmount\"");
        assertThat(saved.getRetryCount()).isZero();
    }
}
