package com.shop.domain.order.controller.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.shop.global.idempotency.IdempotencyExecutor;
import com.shop.global.idempotency.IdempotencyRecord;
import com.shop.global.idempotency.OrderWriteIdempotencyGuard;
import com.shop.global.security.SecurityUtil;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 *
 * <h3>[P0] 멱등성 키(Idempotency Key) 패턴 적용</h3>
 *
 * <p><b>문제:</b> 네트워크 타임아웃, 클라이언트 재시도, 모바일 더블 탭 등으로
 * 동일한 주문 생성 요청이 중복 전송되면 같은 사용자에게 중복 주문이 생성된다.</p>
 *
 * <p><b>해결:</b> 클라이언트가 {@code X-Idempotency-Key} 헤더에 UUID를 전달하면,
 * 서버는 (userId, key) 조합으로 중복을 감지하여 동일한 응답을 반환한다.
 * Stripe, PayPal 등 결제 API에서 표준적으로 사용되는 패턴이다.</p>
 *
 * <p>멱등성 흐름의 공통 보일러플레이트(키 검증 → 기존 레코드 조회 → 상태 분기 →
 * 레코드 생성 → 실행 → 실패 처리)는 {@link IdempotencyExecutor}에 위임한다.</p>
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderApiController {

    private static final Logger log = LoggerFactory.getLogger(OrderApiController.class);

    private final OrderService orderService;
    private final IdempotencyExecutor idempotencyExecutor;
    private final OrderWriteIdempotencyGuard orderWriteIdempotencyGuard;
    private final ObjectMapper objectMapper;

    public OrderApiController(OrderService orderService,
                              IdempotencyExecutor idempotencyExecutor,
                              OrderWriteIdempotencyGuard orderWriteIdempotencyGuard,
                              ObjectMapper objectMapper) {
        this.orderService = orderService;
        this.idempotencyExecutor = idempotencyExecutor;
        this.orderWriteIdempotencyGuard = orderWriteIdempotencyGuard;
        this.objectMapper = objectMapper;
    }

    /**
     * 주문 생성 (멱등성 보장).
     *
     * <p>{@code X-Idempotency-Key} 헤더가 있으면 멱등성 키 패턴을 적용한다.
     * 헤더가 없으면 기존 동작(비멱등)으로 폴백하여 하위 호환성을 유지한다.</p>
     *
     * <p><b>클라이언트 사용법:</b></p>
     * <pre>
     * POST /api/v1/orders
     * X-Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
     * Content-Type: application/json
     *
     * { "shippingAddress": "...", ... }
     * </pre>
     *
     * <p><b>응답 분기:</b></p>
     * <ul>
     *   <li>최초 요청 → 주문 생성 → 201 Created</li>
     *   <li>동일 키 + 이전 성공 → 캐시된 201 응답 반환 (재처리 없음)</li>
     *   <li>동일 키 + 이전 처리 중 → 409 Conflict</li>
     *   <li>동일 키 + 이전 실패 → FAILED 레코드 삭제 후 재처리</li>
     * </ul>
     */
    @PostMapping
    public ResponseEntity<ApiResponse<OrderDetailResponse>> createOrder(
            @Valid @RequestBody OrderCreateRequest request,
            @RequestHeader(name = "X-Idempotency-Key", required = false) String idempotencyKey) {

        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();

        // 멱등성 키가 없으면 기존 비멱등 동작으로 폴백 (하위 호환)
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            orderWriteIdempotencyGuard.handleMissingKey("api", "create", userId);
            Order order = orderService.createOrder(userId, request);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.ok(OrderDetailResponse.from(order)));
        }

        return idempotencyExecutor.execute(
                userId, idempotencyKey, "ORDER", HttpStatus.CREATED.value(),
                this::deserializeCachedResponse,
                () -> orderService.createOrder(userId, request),
                Order::getOrderId,
                OrderDetailResponse::from);
    }

    /**
     * 캐시된 JSON 응답을 역직렬화하여 이전과 동일한 응답을 반환한다.
     *
     * <p>역직렬화 실패 시 DB에서 주문을 직접 조회하여 응답을 재구성한다.
     * 이 경우 응답 본문은 최초 응답과 미세하게 다를 수 있지만(타임스탬프 등),
     * 중복 주문이 생성되지 않는 것이 핵심 목적이므로 허용한다.</p>
     */
    private ResponseEntity<ApiResponse<OrderDetailResponse>> deserializeCachedResponse(
            IdempotencyRecord record) {
        if (record.getResponseBody() != null) {
            try {
                @SuppressWarnings("unchecked")
                ApiResponse<OrderDetailResponse> cached = objectMapper.readValue(
                        record.getResponseBody(),
                        objectMapper.getTypeFactory().constructParametricType(
                                ApiResponse.class, OrderDetailResponse.class));
                return ResponseEntity.status(record.getHttpStatus()).body(cached);
            } catch (JsonProcessingException e) {
                log.warn("캐시된 멱등성 응답 역직렬화 실패 — DB 조회로 폴백. recordId={}",
                        record.getRecordId(), e);
            }
        }

        // 폴백: DB에서 직접 조회하여 응답 재구성
        if (record.getResourceId() != null) {
            Order order = orderService.getOrderDetail(record.getResourceId(), record.getUserId());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.ok(OrderDetailResponse.from(order)));
        }

        // 리소스 ID조차 없는 경우 (극히 드뭄) — 성공 상태만 반환
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(null));
    }

    /**
     * 내 주문 목록 조회.
     *
     * <p>[Phase 18] CQRS: Page&lt;Order&gt; + fetchOrderItems() 2-쿼리 패턴을
     * Page&lt;OrderListReadModel&gt; 단일 쿼리로 대체. 아이템 수와 대표 상품명이
     * v_order_list 뷰의 서브쿼리로 미리 계산되어 추가 쿼리가 불필요하다.</p>
     */
    @GetMapping
    public ApiResponse<PageResponse<OrderSummaryResponse>> getOrders(
            @RequestParam(defaultValue = "0") int page) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        int normalizedPage = PagingParams.normalizePage(page);
        return ApiResponse.ok(PageResponse.from(
                orderService.getOrdersByUserFlat(userId,
                        PageRequest.of(normalizedPage, PageDefaults.DEFAULT_LIST_SIZE)),
                OrderSummaryResponse::from));
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
     * 주문 취소 (멱등성 보장).
     *
     * <p>[Phase 14] 취소 요청에도 멱등성 키를 적용한다.
     * 네트워크 타임아웃으로 클라이언트가 취소 결과를 모르는 상태에서
     * 재시도하면 이미 취소된 주문에 대해 CANCEL_FAIL 에러가 발생한다.
     * 멱등성 키가 있으면 첫 번째 취소 성공 후 재시도 시 동일 응답을 반환한다.</p>
     */
    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<ApiResponse<Void>> cancelOrder(
            @PathVariable Long orderId,
            @RequestHeader(name = "X-Idempotency-Key", required = false) String idempotencyKey) {

        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            orderWriteIdempotencyGuard.handleMissingKey("api", "cancel", userId);
            orderService.cancelOrder(orderId, userId);
            return ResponseEntity.ok(ApiResponse.ok());
        }

        return idempotencyExecutor.executeVoid(
                userId, idempotencyKey, "ORDER_CANCEL",
                HttpStatus.OK.value(), orderId,
                () -> orderService.cancelOrder(orderId, userId));
    }

    /**
     * 부분 취소 (멱등성 보장).
     *
     * <p>[Phase 14] 부분 취소에도 멱등성 키를 적용한다.
     * 부분 취소는 특정 주문항목의 수량을 차감하는 비가역적 연산이므로,
     * 중복 실행 시 과다 취소가 발생할 수 있다. 멱등성 키로 이를 방지한다.</p>
     */
    @PostMapping("/{orderId}/partial-cancel")
    public ResponseEntity<ApiResponse<Void>> partialCancel(
            @PathVariable Long orderId,
            @Valid @RequestBody PartialCancelRequest request,
            @RequestHeader(name = "X-Idempotency-Key", required = false) String idempotencyKey) {

        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            orderWriteIdempotencyGuard.handleMissingKey("api", "partial_cancel", userId);
            orderService.partialCancel(orderId, userId, request.orderItemId(), request.quantity());
            return ResponseEntity.ok(ApiResponse.ok());
        }

        return idempotencyExecutor.executeVoid(
                userId, idempotencyKey, "ORDER_PARTIAL_CANCEL",
                HttpStatus.OK.value(), orderId,
                () -> orderService.partialCancel(orderId, userId, request.orderItemId(), request.quantity()));
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
