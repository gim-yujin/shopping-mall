package com.shop.domain.search.controller.api;

import com.shop.domain.product.dto.ProductListReadModel;
import com.shop.domain.product.dto.ProductSummaryResponse;
import com.shop.domain.product.service.ProductQueryService;
import com.shop.domain.search.service.SearchService;
import com.shop.global.common.PagingParams;
import com.shop.global.dto.ApiResponse;
import com.shop.global.dto.PageResponse;
import com.shop.global.security.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 검색 REST API 컨트롤러.
 *
 * <p>기존 SearchController(SSR)가 Thymeleaf 뷰를 반환하는 반면,
 * 이 컨트롤러는 JSON 응답을 반환한다.
 * 검색 결과는 ProductQueryService의 FTS(Full-Text Search)를 사용하며,
 * 인기 검색어는 SearchService의 캐시된 결과를 반환한다.</p>
 *
 * <p>인증 불필요: SecurityConfig에서 /api/v1/search/** 경로를 permitAll로 설정한다.</p>
 */
@RestController
@RequestMapping("/api/v1/search")
public class SearchApiController {

    private final ProductQueryService productQueryService;
    private final SearchService searchService;

    public SearchApiController(ProductQueryService productQueryService,
                               SearchService searchService) {
        this.productQueryService = productQueryService;
        this.searchService = searchService;
    }

    /**
     * 상품 검색.
     *
     * <p>검색 결과의 첫 페이지 조회 시에만 검색 로그를 기록한다.
     * 페이지네이션 탐색 시 중복 로그를 방지하기 위함이다.</p>
     *
     * @param q    검색 키워드
     * @param page 페이지 번호 (0-based)
     * @param size 페이지 크기
     */
    @GetMapping
    public ApiResponse<PageResponse<ProductSummaryResponse>> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest httpRequest) {

        int normalizedPage = PagingParams.normalizePage(page);
        int normalizedSize = PagingParams.normalizeSize(size);

        Page<ProductListReadModel> results = productQueryService.search(
                q, PageRequest.of(normalizedPage, normalizedSize));

        // 첫 페이지 조회 시에만 검색 로그 기록
        if (normalizedPage == 0) {
            Long userId = SecurityUtil.getCurrentUserId().orElse(null);
            searchService.logSearch(userId, q, (int) results.getTotalElements(),
                    httpRequest.getRemoteAddr(), httpRequest.getHeader("User-Agent"));
        }

        return ApiResponse.ok(PageResponse.from(results, ProductSummaryResponse::from));
    }

    /**
     * 인기 검색어 조회.
     *
     * @return 상위 10개 인기 검색어 목록
     */
    @GetMapping("/popular")
    public ApiResponse<List<String>> getPopularKeywords() {
        return ApiResponse.ok(searchService.getPopularKeywords());
    }
}
