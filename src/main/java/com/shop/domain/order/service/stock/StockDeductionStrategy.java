package com.shop.domain.order.service.stock;

import java.util.List;

/**
 * 재고 차감 전략 인터페이스.
 *
 * <p>동일한 재고 차감 로직을 서로 다른 동시성 제어 전략으로 구현하여
 * 벤치마크를 통해 처리량·레이턴시·정합성을 비교한다.</p>
 *
 * <ul>
 *   <li>V1 — 비관적 잠금 (PESSIMISTIC_WRITE, SELECT ... FOR UPDATE)</li>
 *   <li>V2 — 낙관적 잠금 + 재시도 (@Version + exponential backoff)</li>
 *   <li>V3 — Atomic CAS UPDATE (단일 UPDATE 문의 WHERE 조건으로 원자적 차감)</li>
 * </ul>
 */
public interface StockDeductionStrategy {

    /**
     * 주어진 상품 목록의 재고를 차감한다.
     *
     * @param items 차감할 상품 ID와 수량 목록 (productId 오름차순 정렬 전제)
     * @return 차감 결과 (상품별 before/after 재고)
     * @throws com.shop.global.exception.InsufficientStockException 재고 부족 시
     */
    List<DeductionResult> deductStock(List<DeductionRequest> items);

    /** 벤치마크 결과 출력용 전략 이름. */
    String strategyName();

    record DeductionRequest(Long productId, int quantity) {}

    record DeductionResult(Long productId, int beforeStock, int afterStock) {}
}
