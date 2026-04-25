package com.shop.domain.flashsale.exception;

import com.shop.global.exception.BusinessException;

/**
 * [Phase 23-2] L5 UNIQUE(uk_fsp_user_sale) 위반 — 한 사용자가 동일 세일에 이미 구매한 상태.
 *
 * <p>HTTP 409 CONFLICT로 매핑한다.</p>
 */
public class DuplicateFlashSalePurchaseException extends BusinessException {
    public DuplicateFlashSalePurchaseException(Long flashSaleId, Long userId) {
        super("ONE_PER_USER",
                "한 명당 1건만 구매할 수 있습니다. (saleId=" + flashSaleId + ", userId=" + userId + ")");
    }
}
