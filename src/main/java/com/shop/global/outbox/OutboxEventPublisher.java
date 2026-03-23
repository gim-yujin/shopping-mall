package com.shop.global.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Outbox 이벤트 발행자.
 *
 * <h3>역할</h3>
 * <p>기존 {@code ApplicationEventPublisher.publishEvent()}를 대체한다.
 * 인메모리 이벤트 대신 {@code outbox_events} 테이블에 INSERT하여
 * 비즈니스 데이터와 이벤트를 같은 트랜잭션에서 원자적으로 저장한다.</p>
 *
 * <h3>호출부 변경</h3>
 * <pre>
 *   [이전] Spring ApplicationEvent 발행
 *   [현재] outboxEventPublisher.publishStockChanged(productIds)
 * </pre>
 *
 * <h3>왜 ApplicationEventPublisher를 완전히 대체하는가?</h3>
 * <p>기존 Spring 이벤트를 유지하면서 Outbox를 추가하면 이벤트가 두 번 처리된다
 * (Spring 리스너 + Outbox 폴러). 단일 경로로 통일하여 처리 순서와
 * 멱등성을 보장한다.</p>
 *
 * <h3>트랜잭션 참여</h3>
 * <p>이 컴포넌트는 자체 {@code @Transactional}이 없다.
 * 호출하는 서비스(OrderCreationService 등)의 트랜잭션에 참여하여
 * outbox INSERT가 비즈니스 데이터와 함께 커밋/롤백된다.</p>
 */
@Component
public class OutboxEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxEventPublisher(OutboxEventRepository outboxEventRepository,
                                 ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 상품 재고 변경 이벤트를 Outbox에 기록한다.
     *
     * <p>주문 생성/취소/부분취소 시 재고가 변경된 상품 ID 목록을
     * JSON 페이로드로 직렬화하여 저장한다.
     * 폴러({@link OutboxEventPoller})가 이 레코드를 읽어
     * 상품 상세 캐시를 무효화한다.</p>
     *
     * <p>호출 시점에 비즈니스 트랜잭션이 활성 상태이므로,
     * 이 INSERT는 주문 데이터와 함께 원자적으로 커밋된다.
     * 트랜잭션이 롤백되면 Outbox 레코드도 함께 롤백된다.</p>
     *
     * @param productIds 재고가 변경된 상품 ID 목록
     */
    public void publishStockChanged(List<Long> productIds) {
        String payload = serializePayload(Map.of("productIds", productIds));
        OutboxEvent event = new OutboxEvent(OutboxEvent.TYPE_PRODUCT_STOCK_CHANGED, payload);
        outboxEventRepository.save(event);
    }

    /**
     * [Phase 6] 주문 생성 이벤트를 Outbox에 기록한다.
     *
     * <p><b>이중 경로 전략:</b> ApplicationEvent(@Async)는 내부 후처리(등급 재계산)를 담당하고,
     * Outbox는 외부 연동(알림 발송)을 담당한다.
     * 내부 후처리는 실패 시 TierScheduler가 보정하므로 best-effort로 충분하지만,
     * 외부 알림은 유실되면 사용자 경험에 직접 영향을 미치므로
     * Outbox의 at-least-once 보장이 필요하다.</p>
     *
     * @param orderId     주문 ID
     * @param userId      사용자 ID
     * @param finalAmount 최종 결제 금액
     */
    public void publishOrderCreated(Long orderId, Long userId, java.math.BigDecimal finalAmount) {
        String payload = serializePayload(Map.of(
                "orderId", orderId,
                "userId", userId,
                "finalAmount", finalAmount));
        OutboxEvent event = new OutboxEvent(OutboxEvent.TYPE_ORDER_CREATED, payload);
        outboxEventRepository.save(event);
    }

    /**
     * [Phase 6] 주문 취소 이벤트를 Outbox에 기록한다.
     *
     * @param orderId        주문 ID
     * @param userId         사용자 ID
     * @param refundedAmount 환불 금액
     */
    public void publishOrderCancelled(Long orderId, Long userId, java.math.BigDecimal refundedAmount) {
        String payload = serializePayload(Map.of(
                "orderId", orderId,
                "userId", userId,
                "refundedAmount", refundedAmount));
        OutboxEvent event = new OutboxEvent(OutboxEvent.TYPE_ORDER_CANCELLED, payload);
        outboxEventRepository.save(event);
    }

    /**
     * 페이로드를 JSON으로 직렬화한다.
     *
     * <p>직렬화 실패는 프로그래밍 오류(잘못된 데이터 타입 등)이므로
     * RuntimeException으로 전파하여 비즈니스 트랜잭션을 롤백시킨다.
     * 이벤트 없이 비즈니스 데이터만 커밋되면 데이터 불일치가 발생하므로,
     * 여기서는 "조용히 무시"하지 않고 실패를 전파하는 것이 올바르다.</p>
     */
    private String serializePayload(Object data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            // 직렬화 실패 시 트랜잭션 롤백을 유도하여 비즈니스 데이터와 이벤트의 원자성 보장
            throw new IllegalStateException("Outbox 이벤트 페이로드 직렬화 실패", e);
        }
    }
}
