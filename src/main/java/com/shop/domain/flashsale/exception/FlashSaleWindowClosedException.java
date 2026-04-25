package com.shop.domain.flashsale.exception;

import com.shop.global.exception.BusinessException;

/**
 * [Phase 23-2] 세일 시간창 밖(시작 전·종료 후) 또는 ACTIVE 상태가 아닐 때 발생.
 *
 * <p>HTTP 400으로 매핑한다(클라이언트가 더 이상 재시도해도 성공하지 않음을 알림).</p>
 */
public class FlashSaleWindowClosedException extends BusinessException {
    public FlashSaleWindowClosedException(Long flashSaleId) {
        super("WINDOW_CLOSED", "플래시 세일이 진행 중이 아닙니다. (saleId=" + flashSaleId + ")");
    }
}
