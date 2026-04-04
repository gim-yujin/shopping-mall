package com.shop.global.event;

/**
 * [Phase 25] 리뷰 변경 도메인 이벤트 — 상품 평점 재계산 트리거.
 *
 * <p><b>문제:</b> ReviewService.createReview/updateReview/deleteReview()에서
 * 리뷰 저장 후 상품 평점 재계산(2쿼리 + 1업데이트), 캐시 무효화, 버전 범프가
 * 동일 트랜잭션 안에서 실행되어 트랜잭션 범위를 불필요하게 확장했다.
 * 또한 캐시 무효화가 커밋 전에 발생하여 동시 읽기 시 stale 캐시 경합이 가능했다.</p>
 *
 * <p><b>해결:</b> 리뷰 변경 시 이 이벤트를 발행하고,
 * {@code @TransactionalEventListener(AFTER_COMMIT)}에서 후처리를 수행한다.
 * 메인 트랜잭션은 리뷰 저장만 담당하여 락 보유 시간이 단축된다.</p>
 *
 * @param productId 평점 재계산 대상 상품 ID
 */
public record ReviewRatingChangedEvent(Long productId) {
}
