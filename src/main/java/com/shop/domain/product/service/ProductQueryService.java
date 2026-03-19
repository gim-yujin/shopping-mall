package com.shop.domain.product.service;

import com.shop.domain.product.dto.CachedProductDetail;
import com.shop.domain.product.dto.ProductListReadModel;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.global.cache.CacheKeyGenerator;
import com.shop.global.common.PagingParams;
import com.shop.global.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * [Phase 18] 상품 읽기 전용 서비스 — CQRS 읽기 모델 분리.
 *
 * <h3>기존 문제: 읽기/쓰기 경로 혼재</h3>
 * <p>ProductService가 읽기(@Cacheable 목록 조회)와 쓰기(@CacheEvict 상품 등록/수정)를
 * 모두 담당했다. 이로 인해:</p>
 * <ul>
 *   <li><b>관심사 혼재</b>: 읽기 최적화(캐시, 프로젝션)와 쓰기 로직(검증, 잠금)이 한 클래스에 공존</li>
 *   <li><b>확장성 제약</b>: 읽기 부하가 높아져도 쓰기 서비스와 분리하여 스케일 아웃 불가</li>
 *   <li><b>트랜잭션 최적화 어려움</b>: readOnly=true가 클래스 레벨이라 개별 메서드의
 *       쓰기 트랜잭션에서 @Transactional을 덮어써야 했음</li>
 * </ul>
 *
 * <h3>해결: 읽기 서비스 분리 (CQRS Query Side)</h3>
 * <p>OrderQueryService 패턴을 따라 읽기 전용 서비스를 분리한다.
 * 모든 @Cacheable 메서드를 이 서비스로 이동하고, 네이티브 SQL 프로젝션으로
 * ProductListReadModel을 직접 생성하여 JPA 엔티티 오버헤드를 제거한다.</p>
 *
 * <h3>캐시 호환성</h3>
 * <p>캐시 이름(bestSellers, productDetail 등)과 키 전략은 그대로 유지한다.
 * ProductService의 @CacheEvict는 캐시 이름으로 동작하므로, 어떤 서비스가
 * 캐시를 채우든 정상적으로 무효화된다.</p>
 *
 * <h3>읽기 모델의 이점</h3>
 * <ul>
 *   <li>필요한 컬럼만 SELECT → 네트워크/메모리 절감</li>
 *   <li>썸네일 서브쿼리 → images 컬렉션 N+1 원천 차단</li>
 *   <li>JPA 스냅샷 미보관 → GC 부담 감소</li>
 *   <li>불변 record → 캐시 데이터 오염 원천 차단</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class ProductQueryService {

    private static final Logger log = LoggerFactory.getLogger(ProductQueryService.class);

    private final ProductRepository productRepository;

    public ProductQueryService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    // ── 홈 페이지 캐시 ────────────────────────────────────────

    /**
     * [Phase 18] 베스트셀러 목록 — 읽기 모델 반환.
     *
     * <p>기존 ProductService.getBestSellers()를 대체한다.
     * Page&lt;Product&gt; 대신 Page&lt;ProductListReadModel&gt;을 캐시에 저장하여
     * JPA 프록시/스냅샷 없이 순수 데이터만 보관한다.</p>
     */
    @Cacheable(value = "bestSellers",
            key = "T(com.shop.global.cache.CacheKeyGenerator).pageable(#pageable)", sync = true)
    public Page<ProductListReadModel> getBestSellers(Pageable pageable) {
        return productRepository.findBestSellersFlat(pageable)
                .map(ProductListReadModel::fromNativeRow);
    }

    @Cacheable(value = "newArrivals",
            key = "T(com.shop.global.cache.CacheKeyGenerator).pageable(#pageable)", sync = true)
    public Page<ProductListReadModel> getNewArrivals(Pageable pageable) {
        return productRepository.findNewArrivalsFlat(pageable)
                .map(ProductListReadModel::fromNativeRow);
    }

    @Cacheable(value = "deals",
            key = "T(com.shop.global.cache.CacheKeyGenerator).pageable(#pageable)", sync = true)
    public Page<ProductListReadModel> getDeals(Pageable pageable) {
        return productRepository.findDealsFlat(pageable)
                .map(ProductListReadModel::fromNativeRow);
    }

    // ── 상품 목록 캐시 ────────────────────────────────────────

    /**
     * [Phase 18] 전체 상품 목록 (정렬 포함) — 읽기 모델 반환.
     *
     * <p>Pageable의 Sort를 네이티브 SQL에 적용하기 위해 직접 PageRequest를 생성한다.
     * v_product_list 뷰의 컬럼명은 엔티티 필드명과 다를 수 있으므로(snake_case),
     * PagingParams.toProductSort()의 결과를 그대로 사용할 수 없다.
     * 대신 sort 문자열 기반으로 적절한 Flat 쿼리를 선택한다.</p>
     */
    @Cacheable(value = "productList", key = "#page + ':' + #size + ':' + #sort", sync = true)
    public Page<ProductListReadModel> findAllSorted(int page, int size, String sort) {
        // [Phase 18] 네이티브 SQL은 snake_case 컬럼명을 사용하므로 toProductSortNative() 사용
        Pageable pageable = PageRequest.of(page, size, PagingParams.toProductSortNative(sort));
        return productRepository.findActiveProductsFlat(pageable)
                .map(ProductListReadModel::fromNativeRow);
    }

    /**
     * [Phase 18] 카테고리별 상품 목록 (정렬 포함) — 읽기 모델 반환.
     */
    @Cacheable(value = "categoryProducts", sync = true,
            key = "#categoryIds.toString() + ':' + #page + ':' + #size + ':' + #sort")
    public Page<ProductListReadModel> findByCategoryIdsSorted(
            List<Integer> categoryIds, int page, int size, String sort) {
        // [Phase 18] 네이티브 SQL은 snake_case 컬럼명을 사용하므로 toProductSortNative() 사용
        Pageable pageable = PageRequest.of(page, size, PagingParams.toProductSortNative(sort));
        return productRepository.findByCategoryIdsFlat(categoryIds, pageable)
                .map(ProductListReadModel::fromNativeRow);
    }

    // ── 검색 캐시 ────────────────────────────────────────────

    /**
     * [Phase 18] 키워드 검색 — 읽기 모델 반환.
     *
     * <p>기존 ProductService.search()의 FTS+LIKE 폴백 로직을 그대로 유지하되,
     * 네이티브 플랫 쿼리를 사용하여 썸네일 N+1을 제거한다.</p>
     */
    @Cacheable(value = "searchResults", sync = true,
            key = "#root.target.searchCacheKey(#keyword, #pageable)")
    public Page<ProductListReadModel> search(String keyword, Pageable pageable) {
        String normalizedKeyword = normalizeSearchKeyword(keyword);

        Page<Object[]> results;
        try {
            results = productRepository.searchByKeywordFlat(normalizedKeyword, pageable);
        } catch (DataAccessException e) {
            log.warn("정규 검색(FTS) 실패로 LIKE 검색으로 폴백합니다. keyword={}", normalizedKeyword, e);
            return productRepository.searchByKeywordLikeFlat(normalizedKeyword, pageable)
                    .map(ProductListReadModel::fromNativeRow);
        }

        if (results.isEmpty()) {
            results = productRepository.searchByKeywordLikeFlat(normalizedKeyword, pageable);
        }
        return results.map(ProductListReadModel::fromNativeRow);
    }

    // ── 상품 상세 캐시 ────────────────────────────────────────

    /**
     * [Phase 18] 상품 상세 — CachedProductDetail 반환 (기존 패턴 유지).
     *
     * <p>상품 상세는 목록보다 많은 필드(description, stockQuantity, viewCount 등)를
     * 필요로 하므로 엔티티 기반 조회를 유지하고 CachedProductDetail로 변환한다.
     * 이미 불변 record이므로 캐시 안전성이 보장된다.</p>
     */
    @Cacheable(value = "productDetail", key = "#productId", sync = true)
    public CachedProductDetail findByIdCached(Long productId) {
        Product product = productRepository.findByIdWithCategory(productId)
                .orElseThrow(() -> new ResourceNotFoundException("상품", productId));
        return CachedProductDetail.from(product);
    }

    // ── 캐시 키 헬퍼 ────────────────────────────────────────

    String normalizeSearchKeyword(String keyword) {
        if (keyword == null) {
            return "";
        }
        return keyword.trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    public String searchCacheKey(String keyword, Pageable pageable) {
        return CacheKeyGenerator.pageableWithPrefix(normalizeSearchKeyword(keyword), pageable);
    }
}
