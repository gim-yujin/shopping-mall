package com.shop.domain.review.controller.api;

import com.shop.domain.review.dto.ReviewCreateRequest;
import com.shop.domain.review.dto.ReviewResponse;
import com.shop.domain.review.entity.Review;
import com.shop.domain.review.service.ReviewService;
import com.shop.global.common.PageDefaults;
import com.shop.global.common.PagingParams;
import com.shop.global.dto.ApiResponse;
import com.shop.global.dto.PageResponse;
import com.shop.global.security.SecurityUtil;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * [P1-6] 리뷰 REST API 컨트롤러.
 *
 * 상품별 리뷰 조회(GET)는 공개 API이며,
 * 리뷰 작성/삭제/도움이 돼요 토글은 인증된 사용자만 가능하다.
 * SecurityConfig에서 GET /api/v1/products/{id}/reviews는 permitAll,
 * 그 외 /api/v1/reviews/** 경로는 authenticated로 설정한다.
 */
@RestController
@RequestMapping("/api/v1")
public class ReviewApiController {

    private final ReviewService reviewService;

    public ReviewApiController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * 상품별 리뷰 목록 조회 (공개).
     */
    @GetMapping("/products/{productId}/reviews")
    public ApiResponse<PageResponse<ReviewResponse>> getProductReviews(
            @PathVariable Long productId,
            @RequestParam(defaultValue = "0") int page) {
        int normalizedPage = PagingParams.normalizePage(page);
        Page<Review> reviews = reviewService.getProductReviews(productId,
                PageRequest.of(normalizedPage, PageDefaults.DEFAULT_LIST_SIZE));
        return ApiResponse.ok(PageResponse.from(reviews, ReviewResponse::from));
    }

    /**
     * 리뷰 작성 (인증 필요).
     */
    @PostMapping("/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReviewResponse> createReview(@Valid @RequestBody ReviewCreateRequest request) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        Review review = reviewService.createReview(userId, request);
        return ApiResponse.ok(ReviewResponse.from(review));
    }

    /**
     * 리뷰 삭제 (인증 필요, 본인 리뷰만).
     */
    @DeleteMapping("/reviews/{reviewId}")
    public ApiResponse<Void> deleteReview(@PathVariable Long reviewId) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        reviewService.deleteReview(reviewId, userId);
        return ApiResponse.ok();
    }

    /**
     * "도움이 돼요" 토글 (인증 필요).
     *
     * @return helpful: 현재 도움이 돼요 상태 (true=등록됨, false=해제됨)
     */
    @PostMapping("/reviews/{reviewId}/helpful")
    public ApiResponse<Map<String, Boolean>> toggleHelpful(@PathVariable Long reviewId) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        boolean helpful = reviewService.markHelpful(reviewId, userId);
        return ApiResponse.ok(Map.of("helpful", helpful));
    }
}
