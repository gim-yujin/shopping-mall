package com.shop.domain.product.service;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * [P1-7] 상품 상세 캐시 무효화 헬퍼.
 *
 * 기존 문제: OrderCreationService와 OrderCancellationService에
 * 동일한 evictProductDetailCaches() 메서드가 private으로 중복 존재하였다.
 * 재고 변경 시 캐시 무효화가 필요한 곳이 늘어나면(예: 관리자 상품 수정, 재입고 등)
 * 동일한 코드가 추가로 복제되어 유지보수 비용이 증가하는 구조였다.
 *
 * 해결: 캐시 무효화 로직을 공유 컴포넌트로 추출하여 단일 책임 원칙을 적용한다.
 * 재고가 변경되는 모든 경로(주문 생성, 주문 취소, 관리자 상품 수정 등)에서
 * 이 헬퍼를 주입받아 일관된 캐시 무효화를 수행한다.
 */
@Component
public class ProductCacheEvictHelper {

    private final CacheManager cacheManager;

    public ProductCacheEvictHelper(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /**
     * 지정된 상품 ID 목록에 대한 상세 캐시를 무효화한다.
     *
     * 재고 수량이 변경된 상품은 캐시된 상세 정보(가격, 재고 등)가
     * 실제 DB 상태와 불일치하므로, 다음 조회 시 DB에서 최신 데이터를 로드하도록
     * 해당 상품의 캐시 엔트리를 제거한다.
     *
     * @param productIds 캐시를 무효화할 상품 ID 목록
     */
    public void evictProductDetailCaches(List<Long> productIds) {
        Cache cache = cacheManager.getCache("productDetail");
        if (cache == null) {
            return;
        }
        for (Long productId : productIds) {
            cache.evict(productId);
        }
    }
}
