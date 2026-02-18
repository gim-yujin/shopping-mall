package com.shop.domain.product.controller;

import com.shop.domain.category.entity.Category;
import com.shop.domain.category.service.CategoryService;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.service.ProductService;
import com.shop.domain.review.service.ReviewService;
import com.shop.domain.review.entity.Review;
import com.shop.domain.wishlist.service.WishlistService;
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

    public ProductController(ProductService productService, CategoryService categoryService,
                             ReviewService reviewService, WishlistService wishlistService) {
        this.productService = productService;
        this.categoryService = categoryService;
        this.reviewService = reviewService;
        this.wishlistService = wishlistService;
    }

    @GetMapping
    public String listProducts(@RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size,
                               @RequestParam(defaultValue = "best") String sort,
                               Model model) {
        model.addAttribute("products", productService.findAllSorted(page, size, sort));
        model.addAttribute("categories", categoryService.getTopLevelCategories());
        model.addAttribute("currentSort", sort);
        model.addAttribute("baseUrl", "/products");
        return "product/list";
    }

    @GetMapping("/{productId}")
    public String productDetail(@PathVariable Long productId,
                                @RequestParam(defaultValue = "0") int reviewPage,
                                Model model) {
        Product product = productService.findByIdAndIncrementView(productId);
        Page<Review> reviews = reviewService.getProductReviews(productId, PageRequest.of(reviewPage, 10));

        model.addAttribute("product", product);
        model.addAttribute("reviews", reviews);
        model.addAttribute("helpedReviewIds", Set.of());

        SecurityUtil.getCurrentUserId().ifPresent(userId -> {
            model.addAttribute("isWishlisted", wishlistService.isWishlisted(userId, productId));

            Set<Long> reviewIds = reviews.getContent().stream()
                    .map(Review::getReviewId)
                    .collect(Collectors.toSet());
            model.addAttribute("helpedReviewIds", reviewService.getHelpedReviewIds(userId, reviewIds));
        });

        if (product.getCategory() != null) {
            model.addAttribute("breadcrumb", categoryService.getBreadcrumb(product.getCategory().getCategoryId()));
        }
        return "product/detail";
    }
}
