package com.shop.domain.wishlist.controller.api;

import com.shop.domain.wishlist.dto.WishlistItemResponse;
import com.shop.domain.wishlist.dto.WishlistListReadModel;
import com.shop.domain.wishlist.service.WishlistService;
import com.shop.global.common.PageDefaults;
import com.shop.global.common.PagingParams;
import com.shop.global.dto.ApiResponse;
import com.shop.global.dto.PageResponse;
import com.shop.global.security.SecurityUtil;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * [P1-6] 위시리스트 REST API 컨트롤러.
 *
 * 기존 WishlistController(SSR)의 /wishlist/toggle은 이미 @ResponseBody로
 * JSON을 반환했으나, GET /wishlist는 Thymeleaf 뷰를 반환하였다.
 * 이 컨트롤러는 모든 위시리스트 연산을 JSON 기반 REST API로 제공한다.
 */
@RestController
@RequestMapping("/api/v1/wishlist")
public class WishlistApiController {

    private final WishlistService wishlistService;

    public WishlistApiController(WishlistService wishlistService) {
        this.wishlistService = wishlistService;
    }

    /**
     * 위시리스트 조회.
     */
    @GetMapping
    public ApiResponse<PageResponse<WishlistItemResponse>> getWishlist(
            @RequestParam(defaultValue = "0") int page) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        int normalizedPage = PagingParams.normalizePage(page);
        Page<WishlistListReadModel> wishlists = wishlistService.getWishlistFlat(userId,
                PageRequest.of(normalizedPage, PageDefaults.LARGE_LIST_SIZE));
        return ApiResponse.ok(PageResponse.from(wishlists, WishlistItemResponse::from));
    }

    /**
     * 위시리스트 토글 (추가/제거).
     *
     * @return wishlisted: 토글 후 위시리스트 등록 상태 (true=추가됨, false=제거됨)
     */
    @PostMapping("/toggle")
    public ApiResponse<Map<String, Boolean>> toggleWishlist(@RequestParam Long productId) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        boolean wishlisted = wishlistService.toggleWishlist(userId, productId);
        return ApiResponse.ok(Map.of("wishlisted", wishlisted));
    }
}
