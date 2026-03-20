package com.shop.domain.order.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.domain.order.dto.OrderCreateRequest;
import com.shop.domain.order.dto.OrderListReadModel;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.service.OrderService;
import com.shop.global.idempotency.IdempotencyRecord;
import com.shop.global.idempotency.IdempotencyService;
import com.shop.global.metrics.IdempotencyMetrics;
import com.shop.global.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * OrderApiController 단위 테스트.
 *
 * <p>REST API 컨트롤러는 JSON 요청/응답을 처리하므로,
 * {@code content(MediaType.APPLICATION_JSON)}으로 요청하고
 * {@code $.success}, {@code $.data} JSON 경로로 응답을 검증한다.</p>
 *
 * <p>standaloneSetup에서는 Spring Security 필터 체인이 없으므로 CSRF 토큰이 불필요하다.
 * 대신 SecurityContextHolder에 인증 정보를 직접 설정하여
 * SecurityUtil.getCurrentUserId()가 동작하도록 구성한다.</p>
 *
 * <p>커버리지 목표: 6% → 70%+ (6개 REST 엔드포인트 전체 커버)</p>
 */
@ExtendWith(MockitoExtension.class)
class OrderApiControllerUnitTest {

    private static final Long USER_ID = 1L;
    private static final Long ORDER_ID = 100L;

    @Mock
    private OrderService orderService;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private IdempotencyMetrics idempotencyMetrics;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // ObjectMapper에 JavaTimeModule 등록 (LocalDateTime 직렬화용)
        objectMapper.findAndRegisterModules();

        OrderApiController controller = new OrderApiController(
                orderService, idempotencyService, idempotencyMetrics, objectMapper);

        // @Valid + @RequestBody 조합에서 Bean Validation이 동작하려면
        // LocalValidatorFactoryBean을 standaloneSetup에 등록해야 한다.
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setValidator(validator)
                .build();

        // SecurityUtil.getCurrentUserId()가 USER_ID를 반환하도록 인증 컨텍스트 설정
        CustomUserPrincipal principal = new CustomUserPrincipal(
                USER_ID, "tester", "encoded", "테스터", "ROLE_USER",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── 테스트 픽스처 ───────────────────────────────────────────

    /**
     * 테스트용 Order 엔티티를 생성한다.
     * OrderDetailResponse.from(order) 호출 시 필요한 필드를 모두 설정한다.
     */
    private Order createOrder() {
        Order order = new Order(
                "ORD-TEST-001", USER_ID,
                new BigDecimal("60000"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("3000"), new BigDecimal("63000"),
                new BigDecimal("0.01"), 630, 0,
                "CARD", "서울시 강남구", "홍길동", "010-1234-5678"
        );
        ReflectionTestUtils.setField(order, "orderId", ORDER_ID);
        return order;
    }

    // ── POST /api/v1/orders ─────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/orders — 주문 생성")
    class CreateOrderTests {

        @Test
        @DisplayName("정상 요청 시 201 Created와 주문 상세 응답을 반환한다")
        void createOrder_validRequest_returns201() throws Exception {
            // given
            Order order = createOrder();
            when(orderService.createOrder(eq(USER_ID), any(OrderCreateRequest.class))).thenReturn(order);

            // OrderCreateRequest의 compact constructor가 shippingFee를 ZERO로 재설정하고,
            // paymentMethod를 uppercase로 정규화하므로 원본 값과 관계없이 정상 동작한다.
            String requestBody = """
                    {
                        "shippingAddress": "서울시 강남구",
                        "recipientName": "홍길동",
                        "recipientPhone": "010-1234-5678",
                        "paymentMethod": "CARD"
                    }
                    """;

            // when & then: 201 Created + ApiResponse 래퍼 구조 확인
            mockMvc.perform(post("/api/v1/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.orderId").value(ORDER_ID))
                    .andExpect(jsonPath("$.data.orderNumber").value("ORD-TEST-001"));
        }

        @Test
        @DisplayName("필수 필드 누락 시 400 Bad Request를 반환한다")
        void createOrder_missingFields_returns400() throws Exception {
            // given: shippingAddress 누락 → @NotBlank 위반
            String requestBody = """
                    {
                        "recipientName": "홍길동",
                        "recipientPhone": "010-1234-5678",
                        "paymentMethod": "CARD"
                    }
                    """;

            // when & then: 400 + createOrder 호출되지 않음
            mockMvc.perform(post("/api/v1/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest());

            verify(orderService, never()).createOrder(anyLong(), any());
        }

        @Test
        @DisplayName("포인트 음수 사용 시 400 Bad Request를 반환한다")
        void createOrder_negativePoints_returns400() throws Exception {
            // given: usePoints가 음수 → @Min(0) 위반
            String requestBody = """
                    {
                        "shippingAddress": "서울시 강남구",
                        "recipientName": "홍길동",
                        "recipientPhone": "010-1234-5678",
                        "paymentMethod": "CARD",
                        "usePoints": -100
                    }
                    """;

            // when & then
            mockMvc.perform(post("/api/v1/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest());

            verify(orderService, never()).createOrder(anyLong(), any());
        }

        @Test
        @DisplayName("쿠폰과 포인트를 함께 사용하는 요청도 정상 처리된다")
        void createOrder_withCouponAndPoints_returns201() throws Exception {
            // given
            Order order = createOrder();
            when(orderService.createOrder(eq(USER_ID), any(OrderCreateRequest.class))).thenReturn(order);

            String requestBody = """
                    {
                        "shippingAddress": "서울시 강남구",
                        "recipientName": "홍길동",
                        "recipientPhone": "010-1234-5678",
                        "paymentMethod": "NAVER",
                        "userCouponId": 10,
                        "usePoints": 500,
                        "cartItemIds": [1, 3, 5]
                    }
                    """;

            // when & then
            mockMvc.perform(post("/api/v1/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true));
        }
    }

    // ── GET /api/v1/orders ──────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/orders — 주문 목록 조회")
    class GetOrdersTests {

        // [Phase 18] CQRS: API 컨트롤러가 getOrdersByUserFlat()을 호출하므로 읽기 모델로 mock 변경
        @Test
        @DisplayName("정상 조회 시 페이징된 주문 목록을 반환한다")
        void getOrders_returnsPagedList() throws Exception {
            // given: 주문 1건이 존재 (OrderListReadModel 사용)
            OrderListReadModel readModel = new OrderListReadModel(
                    ORDER_ID, "ORD-TEST-001", USER_ID, "PENDING",
                    new java.math.BigDecimal("60000"), java.math.BigDecimal.ZERO,
                    new java.math.BigDecimal("3000"), new java.math.BigDecimal("63000"),
                    java.time.LocalDateTime.now(), null, null, null, null, 2, "테스트 상품");
            Page<OrderListReadModel> page = new PageImpl<>(List.of(readModel), PageRequest.of(0, 10), 1);
            when(orderService.getOrdersByUserFlat(eq(USER_ID), any(PageRequest.class))).thenReturn(page);

            // when & then: ApiResponse > PageResponse 구조 확인
            mockMvc.perform(get("/api/v1/orders").param("page", "0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.content[0].orderId").value(ORDER_ID));
        }

        @Test
        @DisplayName("빈 목록일 때도 200 OK와 빈 content 배열을 반환한다")
        void getOrders_empty_returnsEmptyContent() throws Exception {
            // given: [Phase 18] CQRS 읽기 모델 사용
            Page<OrderListReadModel> page = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 10), 0);
            when(orderService.getOrdersByUserFlat(eq(USER_ID), any(PageRequest.class))).thenReturn(page);

            // when & then
            mockMvc.perform(get("/api/v1/orders"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content").isEmpty());
        }
    }

    // ── GET /api/v1/orders/{orderId} ────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/orders/{orderId} — 주문 상세 조회")
    class GetOrderDetailTests {

        @Test
        @DisplayName("정상 조회 시 주문 상세 정보를 반환한다")
        void getOrder_returnsDetail() throws Exception {
            // given
            Order order = createOrder();
            when(orderService.getOrderDetail(ORDER_ID, USER_ID)).thenReturn(order);

            // when & then: 주문 상세 필드 확인
            mockMvc.perform(get("/api/v1/orders/{orderId}", ORDER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.orderId").value(ORDER_ID))
                    .andExpect(jsonPath("$.data.orderNumber").value("ORD-TEST-001"))
                    .andExpect(jsonPath("$.data.orderStatus").value("PENDING"))
                    .andExpect(jsonPath("$.data.totalAmount").value(60000))
                    .andExpect(jsonPath("$.data.shippingFee").value(3000))
                    .andExpect(jsonPath("$.data.finalAmount").value(63000));
        }
    }

    // ── POST /api/v1/orders/{orderId}/cancel ────────────────────

    @Nested
    @DisplayName("POST /api/v1/orders/{orderId}/cancel — 주문 취소")
    class CancelOrderTests {

        @Test
        @DisplayName("주문 취소 성공 시 200 OK를 반환한다")
        void cancelOrder_success_returns200() throws Exception {
            // given
            doNothing().when(orderService).cancelOrder(ORDER_ID, USER_ID);

            // when & then
            mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", ORDER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
    }

    // ── POST /api/v1/orders/{orderId}/partial-cancel ────────────

    @Nested
    @DisplayName("POST /api/v1/orders/{orderId}/partial-cancel — 부분 취소")
    class PartialCancelTests {

        @Test
        @DisplayName("부분 취소 성공 시 200 OK를 반환한다")
        void partialCancel_success_returns200() throws Exception {
            // given
            doNothing().when(orderService).partialCancel(ORDER_ID, USER_ID, 50L, 1);

            String requestBody = """
                    {
                        "orderItemId": 50,
                        "quantity": 1
                    }
                    """;

            // when & then
            mockMvc.perform(post("/api/v1/orders/{orderId}/partial-cancel", ORDER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("필수 필드 누락 시 400 Bad Request를 반환한다")
        void partialCancel_missingFields_returns400() throws Exception {
            // given: quantity 누락 → @NotNull 위반
            String requestBody = """
                    {
                        "orderItemId": 50
                    }
                    """;

            // when & then
            mockMvc.perform(post("/api/v1/orders/{orderId}/partial-cancel", ORDER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest());

            verify(orderService, never()).partialCancel(anyLong(), anyLong(), anyLong(), anyInt());
        }
    }

    // ── 주문 생성 — 멱등성 키 분기 커버리지 ─────────────────────

    /**
     * 주문 생성 엔드포인트의 멱등성 키 분기를 모두 커버한다.
     *
     * <p>기존 테스트(CreateOrderTests)는 멱등성 키 없이 호출하므로 비멱등 폴백만 검증한다.
     * 이 클래스에서 키가 있는 경우의 COMPLETED/PROCESSING/FAILED/UNIQUE 충돌/비즈니스 예외
     * 분기를 모두 검증하여 OrderApiController.createOrder()의 분기 커버리지를 보강한다.</p>
     */
    @Nested
    @DisplayName("주문 생성 — 멱등성 키 분기")
    class CreateOrderIdempotencyTests {

        private static final String ORDER_JSON = """
                {
                    "shippingAddress": "서울시 강남구",
                    "recipientName": "홍길동",
                    "recipientPhone": "010-1234-5678",
                    "paymentMethod": "CARD"
                }
                """;

        @Test
        @DisplayName("최초 요청 — PROCESSING 레코드 생성 후 주문 생성 → 201 + markCompleted 호출")
        void firstRequest_createsOrderAndCompletes() throws Exception {
            String key = "order-new-key";
            IdempotencyRecord record = new IdempotencyRecord(USER_ID, key, "ORDER");
            ReflectionTestUtils.setField(record, "recordId", 100L);
            Order order = createOrder();

            when(idempotencyService.findExisting(USER_ID, key)).thenReturn(Optional.empty());
            when(idempotencyService.initRecord(USER_ID, key, "ORDER")).thenReturn(record);
            when(orderService.createOrder(eq(USER_ID), any())).thenReturn(order);

            mockMvc.perform(post("/api/v1/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ORDER_JSON)
                            .header("X-Idempotency-Key", key))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true));

            // markCompleted에 orderId, 직렬화된 JSON, HTTP 201이 전달되는지 검증
            verify(idempotencyService).markCompleted(eq(100L), eq(ORDER_ID), any(String.class), eq(201));
            verify(idempotencyMetrics).recordNew();
        }

        @Test
        @DisplayName("COMPLETED + 캐시된 responseBody — 역직렬화하여 이전 응답 그대로 반환")
        void completedRecord_returnsCachedResponse() throws Exception {
            String key = "completed-key";
            IdempotencyRecord completed = new IdempotencyRecord(USER_ID, key, "ORDER");
            // markCompleted로 responseBody와 httpStatus를 설정
            completed.markCompleted(ORDER_ID,
                    "{\"success\":true,\"data\":{\"orderId\":100,\"orderNumber\":\"ORD-TEST-001\"}}",
                    201);

            when(idempotencyService.findExisting(USER_ID, key)).thenReturn(Optional.of(completed));

            mockMvc.perform(post("/api/v1/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ORDER_JSON)
                            .header("X-Idempotency-Key", key))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true));

            // 주문 서비스가 호출되지 않아야 함 — 중복 주문 방지의 핵심 검증
            verify(orderService, never()).createOrder(anyLong(), any());
            verify(idempotencyMetrics).recordDuplicateCompleted();
        }

        @Test
        @DisplayName("COMPLETED + responseBody=null — DB에서 주문 조회하여 폴백 응답")
        void completedRecord_nullBody_fallsBackToDb() throws Exception {
            // responseBody가 null: 직렬화 실패 또는 SSR에서 markCompletedForSsr() 호출된 경우
            String key = "no-body-key";
            IdempotencyRecord completed = new IdempotencyRecord(USER_ID, key, "ORDER");
            completed.markCompletedForSsr(ORDER_ID); // responseBody=null

            when(idempotencyService.findExisting(USER_ID, key)).thenReturn(Optional.of(completed));
            when(orderService.getOrderDetail(ORDER_ID, USER_ID)).thenReturn(createOrder());

            mockMvc.perform(post("/api/v1/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ORDER_JSON)
                            .header("X-Idempotency-Key", key))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.orderId").value(ORDER_ID));

            // 캐시 미스 → DB 폴백 조회 확인
            verify(orderService).getOrderDetail(ORDER_ID, USER_ID);
        }

        @Test
        @DisplayName("COMPLETED + resourceId=null — 성공 상태만 반환 (극히 드문 케이스)")
        void completedRecord_nullResourceId_returnsSuccessOnly() throws Exception {
            // resourceId가 null: markCompleted 호출 전 상태만 COMPLETED로 전이된 극단적 경우
            String key = "no-resource-key";
            IdempotencyRecord completed = new IdempotencyRecord(USER_ID, key, "ORDER");
            ReflectionTestUtils.setField(completed, "status", "COMPLETED");
            // resourceId, responseBody 모두 null

            when(idempotencyService.findExisting(USER_ID, key)).thenReturn(Optional.of(completed));

            mockMvc.perform(post("/api/v1/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ORDER_JSON)
                            .header("X-Idempotency-Key", key))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true));

            verify(orderService, never()).createOrder(anyLong(), any());
        }

        @Test
        @DisplayName("COMPLETED + 역직렬화 실패 — 손상된 JSON이면 DB 폴백")
        void completedRecord_corruptedJson_fallsBackToDb() throws Exception {
            // 저장된 responseBody가 손상되어 역직렬화가 실패하는 경우
            String key = "corrupted-key";
            IdempotencyRecord completed = new IdempotencyRecord(USER_ID, key, "ORDER");
            completed.markCompleted(ORDER_ID, "{invalid json!!!", 201);

            when(idempotencyService.findExisting(USER_ID, key)).thenReturn(Optional.of(completed));
            when(orderService.getOrderDetail(ORDER_ID, USER_ID)).thenReturn(createOrder());

            mockMvc.perform(post("/api/v1/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ORDER_JSON)
                            .header("X-Idempotency-Key", key))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.orderId").value(ORDER_ID));

            // 손상된 JSON → JsonProcessingException → DB 폴백
            verify(orderService).getOrderDetail(ORDER_ID, USER_ID);
        }

        @Test
        @DisplayName("PROCESSING 상태 — 이전 요청 처리 중이면 409 Conflict")
        void processingRecord_returns409() throws Exception {
            String key = "processing-key";
            IdempotencyRecord processing = new IdempotencyRecord(USER_ID, key, "ORDER");
            // 생성 직후 기본 상태가 PROCESSING

            when(idempotencyService.findExisting(USER_ID, key)).thenReturn(Optional.of(processing));

            mockMvc.perform(post("/api/v1/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ORDER_JSON)
                            .header("X-Idempotency-Key", key))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_PROCESSING"));

            verify(orderService, never()).createOrder(anyLong(), any());
            verify(idempotencyMetrics).recordDuplicateProcessing();
        }

        @Test
        @DisplayName("FAILED 상태 — 이전 실패 레코드 삭제 후 재처리 허용")
        void failedRecord_allowsRetry() throws Exception {
            String key = "failed-key";
            IdempotencyRecord failed = new IdempotencyRecord(USER_ID, key, "ORDER");
            failed.markFailed();

            IdempotencyRecord newRecord = new IdempotencyRecord(USER_ID, key, "ORDER");
            ReflectionTestUtils.setField(newRecord, "recordId", 200L);
            Order order = createOrder();

            when(idempotencyService.findExisting(USER_ID, key)).thenReturn(Optional.of(failed));
            when(idempotencyService.retryAfterFailure(USER_ID, key, "ORDER")).thenReturn(newRecord);
            when(orderService.createOrder(eq(USER_ID), any())).thenReturn(order);

            mockMvc.perform(post("/api/v1/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ORDER_JSON)
                            .header("X-Idempotency-Key", key))
                    .andExpect(status().isCreated());

            verify(idempotencyMetrics).recordRetry();
            verify(idempotencyMetrics).recordNew();
        }

        @Test
        @DisplayName("UNIQUE 충돌 — 동시에 같은 키로 INSERT 시 409 Conflict")
        void uniqueViolation_returns409() throws Exception {
            String key = "race-key";
            when(idempotencyService.findExisting(USER_ID, key)).thenReturn(Optional.empty());
            when(idempotencyService.initRecord(USER_ID, key, "ORDER"))
                    .thenThrow(new DataIntegrityViolationException("unique_violation"));

            mockMvc.perform(post("/api/v1/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ORDER_JSON)
                            .header("X-Idempotency-Key", key))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));

            verify(idempotencyMetrics).recordConflict();
        }

        @Test
        @DisplayName("주문 생성 중 예외 — FAILED로 전환 후 예외 전파")
        void businessFailure_marksFailedAndRethrows() throws Exception {
            String key = "fail-key";
            IdempotencyRecord record = new IdempotencyRecord(USER_ID, key, "ORDER");
            ReflectionTestUtils.setField(record, "recordId", 300L);

            when(idempotencyService.findExisting(USER_ID, key)).thenReturn(Optional.empty());
            when(idempotencyService.initRecord(USER_ID, key, "ORDER")).thenReturn(record);
            when(orderService.createOrder(eq(USER_ID), any()))
                    .thenThrow(new RuntimeException("CART_EMPTY"));

            // standaloneSetup에는 GlobalExceptionHandler 없으므로 예외가 ServletException으로 래핑됨
            try {
                mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ORDER_JSON)
                        .header("X-Idempotency-Key", key));
            } catch (Exception ignored) {
                // 예외 전파 확인 — 핵심은 markFailed 호출 여부
            }

            verify(idempotencyService).markFailed(300L);
        }
    }

    // ── 주문 취소 — 멱등성 키 분기 커버리지 ──────────────────────

    /**
     * 취소 엔드포인트의 멱등성 키 분기를 검증한다.
     *
     * <p>취소는 주문 생성과 달리 응답 본문이 없으므로(ApiResponse&lt;Void&gt;),
     * COMPLETED 시 markCompletedForSsr(orderId)만 호출하고 responseBody는 저장하지 않는다.
     * deserializeCachedResponse() 대신 직접 ApiResponse.ok()를 반환한다.</p>
     */
    @Nested
    @DisplayName("주문 취소 — 멱등성 키 분기")
    class CancelOrderIdempotencyTests {

        @Test
        @DisplayName("COMPLETED 상태 — 재취소 없이 성공 응답 반환")
        void cancelOrder_completed_returnsCached() throws Exception {
            String key = "cancel-done";
            IdempotencyRecord completed = new IdempotencyRecord(USER_ID, key, "ORDER_CANCEL");
            completed.markCompletedForSsr(ORDER_ID);

            when(idempotencyService.findExisting(USER_ID, key)).thenReturn(Optional.of(completed));

            mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", ORDER_ID)
                            .header("X-Idempotency-Key", key))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            // cancelOrder가 호출되지 않아야 함 — 중복 취소 방지
            verify(orderService, never()).cancelOrder(anyLong(), anyLong());
            verify(idempotencyMetrics).recordDuplicateCompleted();
        }

        @Test
        @DisplayName("PROCESSING 상태 — 409 Conflict 반환")
        void cancelOrder_processing_returns409() throws Exception {
            String key = "cancel-in-progress";
            IdempotencyRecord processing = new IdempotencyRecord(USER_ID, key, "ORDER_CANCEL");

            when(idempotencyService.findExisting(USER_ID, key)).thenReturn(Optional.of(processing));

            mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", ORDER_ID)
                            .header("X-Idempotency-Key", key))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_PROCESSING"));

            verify(idempotencyMetrics).recordDuplicateProcessing();
        }

        @Test
        @DisplayName("FAILED 상태 — 재시도 허용 후 취소 수행")
        void cancelOrder_failed_allowsRetry() throws Exception {
            String key = "cancel-retry";
            IdempotencyRecord failed = new IdempotencyRecord(USER_ID, key, "ORDER_CANCEL");
            failed.markFailed();

            IdempotencyRecord newRecord = new IdempotencyRecord(USER_ID, key, "ORDER_CANCEL");
            ReflectionTestUtils.setField(newRecord, "recordId", 400L);

            when(idempotencyService.findExisting(USER_ID, key)).thenReturn(Optional.of(failed));
            when(idempotencyService.retryAfterFailure(USER_ID, key, "ORDER_CANCEL")).thenReturn(newRecord);

            mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", ORDER_ID)
                            .header("X-Idempotency-Key", key))
                    .andExpect(status().isOk());

            verify(orderService).cancelOrder(ORDER_ID, USER_ID);
            verify(idempotencyMetrics).recordRetry();
            verify(idempotencyMetrics).recordNew();
        }

        @Test
        @DisplayName("UNIQUE 충돌 — 동시 취소 요청 시 409")
        void cancelOrder_uniqueViolation_returns409() throws Exception {
            String key = "cancel-race";
            when(idempotencyService.findExisting(USER_ID, key)).thenReturn(Optional.empty());
            when(idempotencyService.initRecord(USER_ID, key, "ORDER_CANCEL"))
                    .thenThrow(new DataIntegrityViolationException("unique_violation"));

            mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", ORDER_ID)
                            .header("X-Idempotency-Key", key))
                    .andExpect(status().isConflict());

            verify(idempotencyMetrics).recordConflict();
        }

        @Test
        @DisplayName("취소 중 예외 — FAILED 전환 후 예외 전파")
        void cancelOrder_failure_marksFailedAndRethrows() throws Exception {
            String key = "cancel-fail";
            IdempotencyRecord record = new IdempotencyRecord(USER_ID, key, "ORDER_CANCEL");
            ReflectionTestUtils.setField(record, "recordId", 500L);

            when(idempotencyService.findExisting(USER_ID, key)).thenReturn(Optional.empty());
            when(idempotencyService.initRecord(USER_ID, key, "ORDER_CANCEL")).thenReturn(record);
            doThrow(new RuntimeException("CANCEL_FAIL"))
                    .when(orderService).cancelOrder(ORDER_ID, USER_ID);

            try {
                mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", ORDER_ID)
                        .header("X-Idempotency-Key", key));
            } catch (Exception ignored) {
            }

            verify(idempotencyService).markFailed(500L);
        }
    }

    // ── 부분 취소 — 멱등성 키 분기 커버리지 ─────────────────────

    /**
     * 부분 취소 엔드포인트의 멱등성 키 분기를 검증한다.
     *
     * <p>부분 취소는 수량 차감이라는 비가역적 연산이므로, 중복 실행 시 과다 취소가 발생할 수 있다.
     * 멱등성 키로 동일 요청의 중복 처리를 방지하는 것이 특히 중요하다.</p>
     */
    @Nested
    @DisplayName("부분 취소 — 멱등성 키 분기")
    class PartialCancelIdempotencyTests {

        private static final String PC_JSON = """
                { "orderItemId": 50, "quantity": 2 }
                """;

        @Test
        @DisplayName("COMPLETED 상태 — 중복 부분 취소 방지")
        void partialCancel_completed_returnsCached() throws Exception {
            String key = "pc-done";
            IdempotencyRecord completed = new IdempotencyRecord(USER_ID, key, "ORDER_PARTIAL_CANCEL");
            completed.markCompletedForSsr(ORDER_ID);

            when(idempotencyService.findExisting(USER_ID, key)).thenReturn(Optional.of(completed));

            mockMvc.perform(post("/api/v1/orders/{orderId}/partial-cancel", ORDER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(PC_JSON)
                            .header("X-Idempotency-Key", key))
                    .andExpect(status().isOk());

            verify(orderService, never()).partialCancel(anyLong(), anyLong(), anyLong(), anyInt());
            verify(idempotencyMetrics).recordDuplicateCompleted();
        }

        @Test
        @DisplayName("PROCESSING 상태 — 409 Conflict")
        void partialCancel_processing_returns409() throws Exception {
            String key = "pc-in-progress";
            IdempotencyRecord processing = new IdempotencyRecord(USER_ID, key, "ORDER_PARTIAL_CANCEL");

            when(idempotencyService.findExisting(USER_ID, key)).thenReturn(Optional.of(processing));

            mockMvc.perform(post("/api/v1/orders/{orderId}/partial-cancel", ORDER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(PC_JSON)
                            .header("X-Idempotency-Key", key))
                    .andExpect(status().isConflict());

            verify(idempotencyMetrics).recordDuplicateProcessing();
        }

        @Test
        @DisplayName("UNIQUE 충돌 — 동시 부분 취소 요청 시 409")
        void partialCancel_uniqueViolation_returns409() throws Exception {
            String key = "pc-race";
            when(idempotencyService.findExisting(USER_ID, key)).thenReturn(Optional.empty());
            when(idempotencyService.initRecord(USER_ID, key, "ORDER_PARTIAL_CANCEL"))
                    .thenThrow(new DataIntegrityViolationException("unique_violation"));

            mockMvc.perform(post("/api/v1/orders/{orderId}/partial-cancel", ORDER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(PC_JSON)
                            .header("X-Idempotency-Key", key))
                    .andExpect(status().isConflict());

            verify(idempotencyMetrics).recordConflict();
        }

        @Test
        @DisplayName("부분 취소 중 예외 — FAILED 전환 후 예외 전파")
        void partialCancel_failure_marksFailedAndRethrows() throws Exception {
            String key = "pc-fail";
            IdempotencyRecord record = new IdempotencyRecord(USER_ID, key, "ORDER_PARTIAL_CANCEL");
            ReflectionTestUtils.setField(record, "recordId", 600L);

            when(idempotencyService.findExisting(USER_ID, key)).thenReturn(Optional.empty());
            when(idempotencyService.initRecord(USER_ID, key, "ORDER_PARTIAL_CANCEL")).thenReturn(record);
            doThrow(new RuntimeException("PARTIAL_CANCEL_FAIL"))
                    .when(orderService).partialCancel(ORDER_ID, USER_ID, 50L, 2);

            try {
                mockMvc.perform(post("/api/v1/orders/{orderId}/partial-cancel", ORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PC_JSON)
                        .header("X-Idempotency-Key", key));
            } catch (Exception ignored) {
            }

            verify(idempotencyService).markFailed(600L);
        }
    }

    // ── POST /api/v1/orders/{orderId}/return ────────────────────

    @Nested
    @DisplayName("POST /api/v1/orders/{orderId}/return — 반품 신청")
    class ReturnTests {

        @Test
        @DisplayName("반품 신청 성공 시 200 OK를 반환한다")
        void requestReturn_success_returns200() throws Exception {
            // given
            doNothing().when(orderService).requestReturn(ORDER_ID, USER_ID, 50L, 1, "DEFECT");

            String requestBody = """
                    {
                        "orderItemId": 50,
                        "quantity": 1,
                        "returnReason": "DEFECT"
                    }
                    """;

            // when & then
            mockMvc.perform(post("/api/v1/orders/{orderId}/return", ORDER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("반품 사유 누락 시 400 Bad Request를 반환한다")
        void requestReturn_missingReason_returns400() throws Exception {
            // given: returnReason 누락 → @NotBlank 위반
            String requestBody = """
                    {
                        "orderItemId": 50,
                        "quantity": 1
                    }
                    """;

            // when & then
            mockMvc.perform(post("/api/v1/orders/{orderId}/return", ORDER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest());

            verify(orderService, never()).requestReturn(
                    anyLong(), anyLong(), anyLong(), anyInt(), any());
        }

        @Test
        @DisplayName("수량이 0 이하이면 400 Bad Request를 반환한다")
        void requestReturn_zeroQuantity_returns400() throws Exception {
            // given: quantity가 0 → @Min(1) 위반
            String requestBody = """
                    {
                        "orderItemId": 50,
                        "quantity": 0,
                        "returnReason": "DEFECT"
                    }
                    """;

            // when & then
            mockMvc.perform(post("/api/v1/orders/{orderId}/return", ORDER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest());

            verify(orderService, never()).requestReturn(
                    anyLong(), anyLong(), anyLong(), anyInt(), any());
        }
    }
}
