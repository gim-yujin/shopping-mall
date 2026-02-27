package com.shop.domain.cart.controller.api;

import com.shop.domain.cart.dto.CartAddRequest;
import com.shop.domain.cart.dto.CartItemResponse;
import com.shop.domain.cart.dto.CartResponse;
import com.shop.domain.cart.entity.Cart;
import com.shop.domain.cart.service.CartService;
import com.shop.global.dto.ApiResponse;
import com.shop.global.security.SecurityUtil;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * [P1-6] 장바구니 REST API 컨트롤러.
 *
 * 기존 CartController(SSR)에서 /cart/update와 /cart/remove는 이미
 * @ResponseBody 또는 리다이렉트로 처리되었으나, 완전한 REST API가 아니었다.
 * 이 컨트롤러는 모든 장바구니 연산을 JSON 기반 REST API로 제공한다.
 */
@RestController
@RequestMapping("/api/v1/cart")
public class CartApiController {

    private final CartService cartService;

    public CartApiController(CartService cartService) {
        this.cartService = cartService;
    }

    /**
     * 장바구니 전체 조회.
     * 항목 목록, 합계 금액, 항목 수를 단일 응답으로 반환한다.
     */
    @GetMapping
    public ApiResponse<CartResponse> getCart() {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        List<Cart> items = cartService.getCartItems(userId);
        BigDecimal totalPrice = cartService.calculateTotal(items);

        List<CartItemResponse> itemResponses = items.stream()
                .map(CartItemResponse::from)
                .toList();

        return ApiResponse.ok(new CartResponse(itemResponses, totalPrice, items.size()));
    }

    /**
     * 장바구니에 상품 추가.
     * 이미 존재하는 상품이면 수량이 누적된다.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CartResponse> addToCart(@Valid @RequestBody CartAddRequest request) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        cartService.addToCart(userId, request.productId(), request.quantity());

        // 추가 후 갱신된 장바구니 상태를 반환
        List<Cart> items = cartService.getCartItems(userId);
        BigDecimal totalPrice = cartService.calculateTotal(items);

        List<CartItemResponse> itemResponses = items.stream()
                .map(CartItemResponse::from)
                .toList();

        return ApiResponse.ok(new CartResponse(itemResponses, totalPrice, items.size()));
    }

    /**
     * 장바구니 상품 수량 변경.
     *
     * @param productId 상품 ID
     * @param quantity  변경할 수량
     */
    @PutMapping("/{productId}")
    public ApiResponse<CartResponse> updateQuantity(
            @PathVariable Long productId,
            @RequestParam int quantity) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        cartService.updateQuantity(userId, productId, quantity);

        List<Cart> items = cartService.getCartItems(userId);
        BigDecimal totalPrice = cartService.calculateTotal(items);

        List<CartItemResponse> itemResponses = items.stream()
                .map(CartItemResponse::from)
                .toList();

        return ApiResponse.ok(new CartResponse(itemResponses, totalPrice, items.size()));
    }

    /**
     * 장바구니에서 상품 제거.
     */
    @DeleteMapping("/{productId}")
    public ApiResponse<Void> removeFromCart(@PathVariable Long productId) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        cartService.removeFromCart(userId, productId);
        return ApiResponse.ok();
    }
}
