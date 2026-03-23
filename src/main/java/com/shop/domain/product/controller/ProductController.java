package com.shop.domain.product.controller;

import com.shop.domain.category.service.CategoryService;
import com.shop.domain.product.dto.CachedProductDetail;
import com.shop.domain.product.service.ProductQueryService;
import com.shop.domain.product.service.ViewCountService;
import com.shop.domain.review.service.ReviewService;
import com.shop.domain.order.entity.OrderItem;
import com.shop.domain.review.entity.Review;
import com.shop.domain.wishlist.service.WishlistService;
import com.shop.global.backpressure.BackpressureDetector;
import com.shop.global.common.PageDefaults;
import com.shop.global.common.PagingParams;
import com.shop.global.security.SecurityUtil;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/products")
public class ProductController {

    /**
     * [Phase 18] 읽기 경로를 ProductQueryService(CQRS Query)로 분리.
     * 목록 조회/상세 조회 모두 읽기 전용이므로 Query 서비스를 사용한다.
     */
    private final ProductQueryService productQueryService;
    private final CategoryService categoryService;
    private final ReviewService reviewService;
    private final WishlistService wishlistService;
    private final ViewCountService viewCountService;
    private final BackpressureDetector backpressureDetector;

    public ProductController(ProductQueryService productQueryService, CategoryService categoryService,
                             ReviewService reviewService, WishlistService wishlistService,
                             ViewCountService viewCountService,
                             BackpressureDetector backpressureDetector) {
        this.productQueryService = productQueryService;
        this.categoryService = categoryService;
        this.reviewService = reviewService;
        this.wishlistService = wishlistService;
        this.viewCountService = viewCountService;
        this.backpressureDetector = backpressureDetector;
    }

    @GetMapping
    public String listProducts(@RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size,
                               @RequestParam(defaultValue = "best") String sort,
                               Model model) {
        int normalizedPage = PagingParams.normalizePage(page);
        int normalizedSize = PagingParams.normalizeSize(size);
        String normalizedSort = PagingParams.normalizeProductSort(sort);

        model.addAttribute("products", productQueryService.findAllSorted(normalizedPage, normalizedSize, normalizedSort));
        model.addAttribute("categories", categoryService.getTopLevelCategories());
        model.addAttribute("currentSort", normalizedSort);
        model.addAttribute("baseUrl", "/products");
        return "product/list";
    }

    @GetMapping("/{productId}")
    public String productDetail(@PathVariable Long productId,
                                @RequestParam(defaultValue = "0") int reviewPage,
                                Model model) {
        // [P0 FIX] 조회수 증가를 캐시 메서드 밖에서 호출하여 매 요청마다 정확히 증가시킨다.
        // 기존: 상세 조회 메서드 내부에서 조회수 증가를 함께 처리 → 캐시 히트 시 조회수 누락
        // 수정: findByIdCached(캐시 조회) + incrementAsync(매 요청 비동기 증가) 분리
        // [P2-7] findByIdCached가 이제 CachedProductDetail 불변 DTO를 반환한다.
        CachedProductDetail product = productQueryService.findByIdCached(productId);
        // [Phase 12] Graceful Degradation: 시스템 과부하 시 조회수 증가를 건너뛴다.
        // 조회수는 약간의 지연/누락이 허용되는 비필수 지표이므로,
        // CRITICAL 상태에서 큐에 작업을 추가하지 않아 핵심 요청 처리를 보호한다.
        if (!backpressureDetector.shouldShedNonCritical()) {
            viewCountService.incrementAsync(productId);
        }
        int normalizedReviewPage = PagingParams.normalizePage(reviewPage);
        Page<Review> reviews = reviewService.getProductReviews(productId, PageRequest.of(normalizedReviewPage, PageDefaults.DEFAULT_LIST_SIZE));

        model.addAttribute("product", product);
        model.addAttribute("reviews", reviews);
        model.addAttribute("helpedReviewIds", Set.of());

        SecurityUtil.getCurrentUserId().ifPresent(userId -> {
            // [3.7] 현재 로그인 사용자 ID를 모델에 추가하여 본인 리뷰 수정/삭제 버튼 표시에 활용
            model.addAttribute("currentUserId", userId);
            model.addAttribute("isWishlisted", wishlistService.isWishlisted(userId, productId));

            Set<Long> reviewIds = reviews.getContent().stream()
                    .map(Review::getReviewId)
                    .collect(Collectors.toSet());
            model.addAttribute("helpedReviewIds", reviewService.getHelpedReviewIds(userId, reviewIds));

            List<OrderItem> reviewableOrderItems = reviewService.getReviewableOrderItems(userId, productId);
            model.addAttribute("reviewableOrderItems", reviewableOrderItems);
        });

        // [P2-7] CachedProductDetail에서 categoryId를 직접 접근 (엔티티 Lazy 프록시 없음)
        if (product.categoryId() != null) {
            model.addAttribute("breadcrumb", categoryService.getBreadcrumb(product.categoryId()));
        }
        return "product/detail";
    }
}
