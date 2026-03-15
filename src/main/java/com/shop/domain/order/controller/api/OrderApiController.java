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
import com.shop.global.exception.BusinessException;
import com.shop.global.idempotency.IdempotencyRecord;
import com.shop.global.idempotency.IdempotencyService;
import com.shop.global.security.SecurityUtil;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

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
 * <p><b>동시 중복 요청 처리 흐름:</b></p>
 * <pre>
 *   요청 A (key=abc) → PROCESSING 레코드 INSERT → 주문 생성 → COMPLETED 전환 → 201 Created
 *   요청 B (key=abc) → INSERT 시도 → UNIQUE 위반 → SELECT → PROCESSING 확인 → 409 Conflict
 *   요청 C (key=abc) → SELECT → COMPLETED 확인 → 캐시된 201 응답 반환
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderApiController {

    private static final Logger log = LoggerFactory.getLogger(OrderApiController.class);

    private final OrderService orderService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public OrderApiController(OrderService orderService,
                              IdempotencyService idempotencyService,
                              ObjectMapper objectMapper) {
        this.orderService = orderService;
        this.idempotencyService = idempotencyService;
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
            Order order = orderService.createOrder(userId, request);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.ok(OrderDetailResponse.from(order)));
        }

        // ── 멱등성 키 패턴 적용 ────────────────────────────

        idempotencyService.validateKey(idempotencyKey);

        // 1단계: 기존 레코드 확인
        Optional<IdempotencyRecord> existing = idempotencyService.findExisting(userId, idempotencyKey);
        if (existing.isPresent()) {
            IdempotencyRecord prev = existing.get();

            if (prev.isCompleted()) {
                // 이전 성공 결과를 캐시에서 반환 — 주문 서비스를 재호출하지 않음
                log.info("멱등성 키 중복 요청 (COMPLETED) - userId={}, key={}, orderId={}",
                        userId, idempotencyKey, prev.getResourceId());
                return deserializeCachedResponse(prev);
            }

            if (prev.isProcessing()) {
                // 이전 요청이 아직 처리 중 — 클라이언트에게 재시도 유도
                log.warn("멱등성 키 중복 요청 (PROCESSING) - userId={}, key={}", userId, idempotencyKey);
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiResponse.error("IDEMPOTENCY_PROCESSING",
                                "이전 주문 요청이 처리 중입니다. 잠시 후 다시 시도해주세요."));
            }

            // FAILED — 이전 실패 레코드를 삭제하고 재처리 허용 (아래 initRecord로 진행)
            log.info("멱등성 키 재시도 (FAILED) - userId={}, key={}", userId, idempotencyKey);
        }

        // 2단계: PROCESSING 레코드 생성 (REQUIRES_NEW 독립 트랜잭션으로 즉시 커밋)
        // existing이 있고 FAILED인 경우: 기존 레코드 삭제 후 새로 생성
        // existing이 없는 경우: 최초 요청으로 새로 생성
        IdempotencyRecord record;
        boolean isRetry = existing.isPresent();
        try {
            record = isRetry
                    ? idempotencyService.retryAfterFailure(userId, idempotencyKey, "ORDER")
                    : idempotencyService.initRecord(userId, idempotencyKey, "ORDER");
        } catch (DataIntegrityViolationException e) {
            // 동시에 같은 키로 INSERT를 시도한 경우 — UNIQUE 위반이 최종 방어선
            log.info("멱등성 키 동시 삽입 충돌 - userId={}, key={}", userId, idempotencyKey);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error("IDEMPOTENCY_CONFLICT",
                            "동일한 요청이 처리 중입니다. 잠시 후 다시 시도해주세요."));
        }

        // 3단계: 주문 생성 실행
        try {
            Order order = orderService.createOrder(userId, request);
            ApiResponse<OrderDetailResponse> response = ApiResponse.ok(OrderDetailResponse.from(order));

            // 4단계: 성공 → COMPLETED 전환 + 응답 JSON 캐시
            String responseJson = serializeResponse(response);
            idempotencyService.markCompleted(record.getRecordId(), order.getOrderId(),
                    responseJson, HttpStatus.CREATED.value());

            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (Exception e) {
            // 5단계: 실패 → FAILED 전환 (주문 트랜잭션은 자동 롤백)
            idempotencyService.markFailed(record.getRecordId());
            throw e;  // 기존 예외 핸들러(ApiExceptionHandler)가 처리
        }
    }

    /**
     * ApiResponse를 JSON 문자열로 직렬화한다.
     *
     * <p>직렬화 실패 시 null을 반환한다. 멱등성 기능의 핵심은
     * 중복 주문 방지이므로, 캐시된 응답이 없더라도 COMPLETED 상태만으로
     * 중복 주문은 차단된다. 다음 재요청 시 DB에서 주문을 조회하여 반환하면 된다.</p>
     */
    private String serializeResponse(ApiResponse<OrderDetailResponse> response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            log.warn("멱등성 응답 직렬화 실패 — 중복 방지는 유지되나 캐시 응답 불가", e);
            return null;
        }
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
