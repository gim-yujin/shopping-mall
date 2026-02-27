package com.shop.domain.product.controller;

import com.shop.domain.category.entity.Category;
import com.shop.domain.category.service.CategoryService;
import com.shop.domain.product.dto.CachedProductDetail;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.service.ProductService;
import com.shop.domain.product.service.ViewCountService;
import com.shop.domain.review.service.ReviewService;
import com.shop.domain.order.entity.OrderItem;
import com.shop.domain.review.entity.Review;
import com.shop.domain.wishlist.service.WishlistService;
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

    private final ProductService productService;
    private final CategoryService categoryService;
    private final ReviewService reviewService;
    private final WishlistService wishlistService;
    private final ViewCountService viewCountService;

    public ProductController(ProductService productService, CategoryService categoryService,
                             ReviewService reviewService, WishlistService wishlistService,
                             ViewCountService viewCountService) {
        this.productService = productService;
        this.categoryService = categoryService;
        this.reviewService = reviewService;
        this.wishlistService = wishlistService;
        this.viewCountService = viewCountService;
    }

    @GetMapping
    public String listProducts(@RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size,
                               @RequestParam(defaultValue = "best") String sort,
                               Model model) {
        int normalizedPage = PagingParams.normalizePage(page);
        int normalizedSize = PagingParams.normalizeSize(size);
        String normalizedSort = PagingParams.normalizeProductSort(sort);

        model.addAttribute("products", productService.findAllSorted(normalizedPage, normalizedSize, normalizedSort));
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
        // 기존: findByIdAndIncrementView() → @Cacheable 내부에서 increment → 캐시 히트 시 조회수 누락
        // 수정: findByIdCached(캐시 조회) + incrementAsync(매 요청 비동기 증가) 분리
        // [P2-7] findByIdCached가 이제 CachedProductDetail 불변 DTO를 반환한다.
        CachedProductDetail product = productService.findByIdCached(productId);
        viewCountService.incrementAsync(productId);
        int normalizedReviewPage = PagingParams.normalizePage(reviewPage);
        Page<Review> reviews = reviewService.getProductReviews(productId, PageRequest.of(normalizedReviewPage, PageDefaults.DEFAULT_LIST_SIZE));

        model.addAttribute("product", product);
        model.addAttribute("reviews", reviews);
        model.addAttribute("helpedReviewIds", Set.of());

        SecurityUtil.getCurrentUserId().ifPresent(userId -> {
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
