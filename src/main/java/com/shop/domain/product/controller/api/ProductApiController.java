package com.shop.domain.product.controller.api;

import com.shop.domain.product.dto.ProductDetailResponse;
import com.shop.domain.product.dto.ProductSummaryResponse;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.service.ProductService;
import com.shop.domain.product.service.ViewCountService;
import com.shop.global.common.PagingParams;
import com.shop.global.dto.ApiResponse;
import com.shop.global.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

/**
 * [P1-6] 상품 REST API 컨트롤러.
 *
 * 기존 ProductController(SSR)와 병행하여 동일한 ProductService를 공유한다.
 * SSR 컨트롤러는 Thymeleaf 뷰를 반환하고, 이 컨트롤러는 JSON을 반환한다.
 *
 * 인증 불필요: 상품 목록/상세 조회는 공개 API로,
 * SecurityConfig에서 /api/v1/products/** 경로를 permitAll로 설정한다.
 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductApiController {

    private final ProductService productService;
    private final ViewCountService viewCountService;

    public ProductApiController(ProductService productService, ViewCountService viewCountService) {
        this.productService = productService;
        this.viewCountService = viewCountService;
    }

    /**
     * 상품 목록 조회 (정렬 지원).
     *
     * @param page 페이지 번호 (0-based)
     * @param size 페이지 크기
     * @param sort 정렬 기준 (best / newest / price_asc / price_desc / review)
     */
    @GetMapping
    public ApiResponse<PageResponse<ProductSummaryResponse>> listProducts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "best") String sort) {

        int normalizedPage = PagingParams.normalizePage(page);
        int normalizedSize = PagingParams.normalizeSize(size);
        String normalizedSort = PagingParams.normalizeProductSort(sort);

        Page<Product> products = productService.findAllSorted(normalizedPage, normalizedSize, normalizedSort);
        return ApiResponse.ok(PageResponse.from(products, ProductSummaryResponse::from));
    }

    /**
     * 상품 상세 조회.
     *
     * [P0 FIX] 조회수 증가를 캐시 메서드 밖에서 호출하여 매 요청마다 정확히 증가시킨다.
     */
    @GetMapping("/{productId}")
    public ApiResponse<ProductDetailResponse> getProduct(@PathVariable Long productId) {
        Product product = productService.findByIdCached(productId);
        viewCountService.incrementAsync(productId);
        return ApiResponse.ok(ProductDetailResponse.from(product));
    }
}
