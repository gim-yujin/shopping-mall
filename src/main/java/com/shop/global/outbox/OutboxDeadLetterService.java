package com.shop.global.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * [Phase 15] Outbox Dead Letter 이벤트 관리 서비스.
 *
 * <h3>왜 Dead Letter 관리 서비스가 필요한가?</h3>
 * <p><b>문제:</b> 기존에 FAILED로 전이된 이벤트를 복구하려면 관리자가 DB에 직접
 * UPDATE 쿼리를 실행해야 했다. 이 방식은:</p>
 * <ul>
 *   <li>운영 DB에 대한 직접 접근 권한이 필요 (보안 위험)</li>
 *   <li>UPDATE 쿼리 오타로 인한 데이터 손상 가능성</li>
 *   <li>복구 이력이 남지 않아 감사(audit) 불가</li>
 * </ul>
 *
 * <p><b>해결:</b> 서비스 계층에 requeueById/requeueAll/discardById 메서드를 제공하여,
 * 관리자 API나 스케줄러를 통한 안전한 Dead Letter 관리를 가능하게 한다.
 * 모든 작업에 로그를 남겨 운영 이력을 추적할 수 있다.</p>
 *
 * <h3>사용 시나리오</h3>
 * <ol>
 *   <li>관리자가 Grafana에서 shop_outbox_dead_letter_count > 0 알림을 수신</li>
 *   <li>findDeadLetterEvents()로 실패 이벤트 목록과 lastError를 확인</li>
 *   <li>원인(외부 서비스 장애 등)이 해소되었으면 requeueAll()로 일괄 재시도</li>
 *   <li>원인이 영구적(잘못된 페이로드 등)이면 discardById()로 개별 폐기</li>
 * </ol>
 */
@Service
public class OutboxDeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(OutboxDeadLetterService.class);

    private final OutboxEventRepository outboxEventRepository;

    public OutboxDeadLetterService(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    /**
     * Dead Letter 이벤트 목록을 조회한다.
     *
     * @param limit 최대 조회 건수
     * @return Dead Letter 이벤트 목록 (최신순)
     */
    @Transactional(readOnly = true)
    public List<OutboxEvent> findDeadLetterEvents(int limit) {
        return outboxEventRepository.findDeadLetterEvents(limit);
    }

    /**
     * 특정 Dead Letter 이벤트를 PENDING으로 되돌려 재처리를 허용한다.
     *
     * <p>retryCount를 0으로 초기화하여 전체 재시도 기회를 다시 부여한다.
     * 장애 원인이 해소된 후 호출해야 한다.</p>
     *
     * @param eventId 재시도할 이벤트 ID
     * @return 재시도 큐에 추가된 경우 true, 이벤트가 없거나 DEAD_LETTER가 아니면 false
     */
    @Transactional
    public boolean requeueById(Long eventId) {
        return outboxEventRepository.findById(eventId)
                .filter(OutboxEvent::isDeadLetter)
                .map(event -> {
                    event.requeueFromDeadLetter();
                    log.info("Dead Letter 이벤트 재시도 큐 등록 - eventId={}, type={}",
                            event.getEventId(), event.getEventType());
                    return true;
                })
                .orElse(false);
    }

    /**
     * 모든 Dead Letter 이벤트를 PENDING으로 일괄 되돌린다.
     *
     * <p>외부 서비스 장애가 해소된 후, 누적된 Dead Letter를 한 번에 재처리할 때 사용한다.
     * 개별 이벤트별로 requeueFromDeadLetter()를 호출하여 상태를 정확하게 전이한다.</p>
     *
     * @return 재시도 큐에 추가된 이벤트 수
     */
    @Transactional
    public int requeueAll() {
        List<OutboxEvent> deadLetters = outboxEventRepository.findDeadLetterEvents(Integer.MAX_VALUE);
        for (OutboxEvent event : deadLetters) {
            event.requeueFromDeadLetter();
        }
        if (!deadLetters.isEmpty()) {
            log.info("Dead Letter 이벤트 일괄 재시도 큐 등록 - count={}", deadLetters.size());
        }
        return deadLetters.size();
    }

    /**
     * 특정 Dead Letter 이벤트를 영구 폐기(삭제)한다.
     *
     * <p>페이로드 오류 등 재시도가 무의미한 이벤트에 사용한다.
     * 삭제 전 이벤트 정보를 로그에 기록하여 감사 추적을 보장한다.</p>
     *
     * @param eventId 폐기할 이벤트 ID
     * @return 폐기된 경우 true, 이벤트가 없거나 DEAD_LETTER가 아니면 false
     */
    @Transactional
    public boolean discardById(Long eventId) {
        return outboxEventRepository.findById(eventId)
                .filter(OutboxEvent::isDeadLetter)
                .map(event -> {
                    log.warn("Dead Letter 이벤트 영구 폐기 - eventId={}, type={}, payload={}, lastError={}",
                            event.getEventId(), event.getEventType(),
                            event.getPayload(), event.getLastError());
                    outboxEventRepository.delete(event);
                    return true;
                })
                .orElse(false);
    }
}
