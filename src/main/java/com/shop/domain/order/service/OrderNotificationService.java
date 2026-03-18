package com.shop.domain.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * [Phase 6] 주문 알림 서비스 (스텁 구현).
 *
 * <p><b>목적:</b> 실제 프로덕션에서는 이메일/SMS/푸시 알림을 발송하지만,
 * 이 프로젝트는 고트래픽/동시성 문제에 집중하므로 로그 출력으로 대체한다.
 * 비동기 이벤트 처리 흐름과 Outbox 이벤트 핸들러의 연동을 검증하는 것이 목적이다.</p>
 *
 * <p><b>확장 방법:</b> 실제 알림이 필요할 때 이 클래스의 메서드 본문만 교체하면 된다.
 * 호출부(OrderPostProcessingListener, OutboxEventHandler)의 변경 없이
 * 알림 채널(이메일 → SMS → 카카오톡)을 전환할 수 있다.</p>
 */
@Service
public class OrderNotificationService {

    private static final Logger log = LoggerFactory.getLogger(OrderNotificationService.class);

    /**
     * 주문 확인 알림을 발송한다 (스텁).
     *
     * @param orderId     주문 ID
     * @param userId      수신자 ID
     * @param finalAmount 결제 금액
     */
    public void sendOrderConfirmation(Long orderId, Long userId, BigDecimal finalAmount) {
        log.info("[알림 스텁] 주문 확인 - orderId={}, userId={}, 결제금액={}",
                orderId, userId, finalAmount);
    }

    /**
     * 주문 취소 알림을 발송한다 (스텁).
     *
     * @param orderId        주문 ID
     * @param userId         수신자 ID
     * @param refundedAmount 환불 금액
     */
    public void sendCancellationNotice(Long orderId, Long userId, BigDecimal refundedAmount) {
        log.info("[알림 스텁] 주문 취소 - orderId={}, userId={}, 환불금액={}",
                orderId, userId, refundedAmount);
    }
}
