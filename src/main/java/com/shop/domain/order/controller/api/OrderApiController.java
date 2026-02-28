package com.shop.domain.order.controller.api;

import com.shop.domain.order.dto.OrderCreateRequest;
import com.shop.domain.order.dto.OrderDetailResponse;
import com.shop.domain.order.dto.OrderSummaryResponse;
import com.shop.domain.order.dto.PartialCancelRequest;
import com.shop.domain.order.dto.ReturnRequest;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.service.OrderService;
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

/**
 * [P1-6] 주문 REST API 컨트롤러.
 *
 * 기존 OrderController(SSR)와 동일한 OrderService를 공유한다.
 * 모든 엔드포인트는 인증된 사용자만 접근 가능하다.
 *
 * 주문 생성(POST)은 기존 OrderCreateRequest를 @RequestBody로 수신한다.
 * SSR에서는 폼 데이터로 바인딩되었으나, REST에서는 JSON으로 바인딩된다.
 * record의 canonical constructor가 동일하게 동작하므로 DTO 재사용이 가능하다.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderApiController {

    private final OrderService orderService;

    public OrderApiController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 주문 생성.
     * 장바구니의 전체 상품을 주문으로 전환한다.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderDetailResponse> createOrder(@Valid @RequestBody OrderCreateRequest request) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        Order order = orderService.createOrder(userId, request);
        return ApiResponse.ok(OrderDetailResponse.from(order));
    }

    /**
     * 내 주문 목록 조회.
     */
    @GetMapping
    public ApiResponse<PageResponse<OrderSummaryResponse>> getOrders(
            @RequestParam(defaultValue = "0") int page) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        int normalizedPage = PagingParams.normalizePage(page);
        Page<Order> orders = orderService.getOrdersByUser(userId,
                PageRequest.of(normalizedPage, PageDefaults.DEFAULT_LIST_SIZE));
        return ApiResponse.ok(PageResponse.from(orders, OrderSummaryResponse::from));
    }

    /**
     * 주문 상세 조회.
     */
    @GetMapping("/{orderId}")
    public ApiResponse<OrderDetailResponse> getOrder(@PathVariable Long orderId) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        Order order = orderService.getOrderDetail(orderId, userId);
        return ApiResponse.ok(OrderDetailResponse.from(order));
    }

    /**
     * 주문 취소.
     */
    @PostMapping("/{orderId}/cancel")
    public ApiResponse<Void> cancelOrder(@PathVariable Long orderId) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        orderService.cancelOrder(orderId, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/{orderId}/partial-cancel")
    public ApiResponse<Void> partialCancel(@PathVariable Long orderId,
                                           @Valid @RequestBody PartialCancelRequest request) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        orderService.partialCancel(orderId, userId, request.orderItemId(), request.quantity());
        return ApiResponse.ok();
    }

    @PostMapping("/{orderId}/return")
    public ApiResponse<Void> requestReturn(@PathVariable Long orderId,
                                           @Valid @RequestBody ReturnRequest request) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        orderService.requestReturn(orderId, userId, request.orderItemId(),
                                    request.quantity(), request.returnReason());
        return ApiResponse.ok();
    }

}
