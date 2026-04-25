package com.shop.domain.flashsale.exception;

import com.shop.global.exception.BusinessException;

/**
 * [Phase 23-2] L4 CAS 예약 실패 — remaining_quantity &lt; requested 로 WHERE 조건 거짓.
 *
 * <p>HTTP 409 CONFLICT로 매핑한다(일시적 자원 경합이 아니라 소진 상태 확정).</p>
 */
public class FlashSaleSoldOutException extends BusinessException {
    public FlashSaleSoldOutException(Long flashSaleItemId) {
        super("SOLD_OUT", "플래시 세일 상품이 모두 소진되었습니다. (itemId=" + flashSaleItemId + ")");
    }
}
