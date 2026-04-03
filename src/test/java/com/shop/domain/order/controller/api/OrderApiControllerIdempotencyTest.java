package com.shop.domain.order.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.service.OrderService;
import com.shop.global.exception.BusinessException;
import com.shop.global.idempotency.IdempotencyRecord;
import com.shop.global.idempotency.IdempotencyService;
import com.shop.global.idempotency.OrderWriteIdempotencyGuard;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * OrderApiController 멱등성 키 패턴 단위 테스트.
 *
 * <p>주문 생성 API에서 X-Idempotency-Key 헤더에 따른 분기 처리를 검증한다:</p>
 * <ul>
 *   <li>키 없음 → 기존 비멱등 동작 (하위 호환)</li>
 *   <li>최초 요청 → PROCESSING 레코드 생성 → 주문 생성 → 201 Created</li>
 *   <li>중복 요청 (COMPLETED) → 캐시된 응답 반환</li>
 *   <li>중복 요청 (PROCESSING) → 409 Conflict</li>
 *   <li>동시 INSERT 충돌 → 409 Conflict</li>
 *   <li>주문 생성 실패 → FAILED 전환 후 예외 전파</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OrderApiControllerIdempotencyTest {

    private static final Long USER_ID = 1L;
    private static final String VALID_KEY = "550e8400-e29b-41d4-a716-446655440000";

    @Mock
    private OrderService orderService;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private OrderWriteIdempotencyGuard orderWriteIdempotencyGuard;

    @Mock
    private IdempotencyMetrics idempotencyMetrics;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // ObjectMapper에 JavaTimeModule 등록 (LocalDateTime 직렬화용)
        objectMapper.findAndRegisterModules();

        OrderApiController controller = new OrderApiController(
                orderService, idempotencyService, orderWriteIdempotencyGuard, idempotencyMetrics, objectMapper);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setValidator(validator)
                // standaloneSetup은 @RestControllerAdvice를 자동 감지하지 않으므로
                // ApiExceptionHandler를 명시적으로 등록하여 BusinessException → 400 JSON 변환을 보장한다.
                .setControllerAdvice(new com.shop.global.exception.ApiExceptionHandler())
                .build();

        // SecurityUtil.getCurrentUserId()가 USER_ID를 반환하도록 설정
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

    // ── 픽스처 헬퍼 ─────────────────────────────────────────────

    private Order createMockOrder(Long orderId) {
        Order order = mock(Order.class);
        lenient().when(order.getOrderId()).thenReturn(orderId);
        lenient().when(order.getOrderNumber()).thenReturn("20260315-TEST");
        lenient().when(order.getUserId()).thenReturn(USER_ID);
        lenient().when(order.getOrderStatusCode()).thenReturn("PAID");
        lenient().when(order.getTotalAmount()).thenReturn(new BigDecimal("30000"));
        lenient().when(order.getDiscountAmount()).thenReturn(BigDecimal.ZERO);
        lenient().when(order.getTierDiscountAmount()).thenReturn(BigDecimal.ZERO);
        lenient().when(order.getCouponDiscountAmount()).thenReturn(BigDecimal.ZERO);
        lenient().when(order.getShippingFee()).thenReturn(new BigDecimal("3000"));
        lenient().when(order.getFinalAmount()).thenReturn(new BigDecimal("33000"));
        lenient().when(order.getPointEarnRateSnapshot()).thenReturn(new BigDecimal("1.50"));
        lenient().when(order.getEarnedPointsSnapshot()).thenReturn(495);
        lenient().when(order.getUsedPoints()).thenReturn(0);
        lenient().when(order.getRefundedAmount()).thenReturn(BigDecimal.ZERO);
        lenient().when(order.getRefundedPoints()).thenReturn(0);
        lenient().when(order.isPointsSettled()).thenReturn(false);
        lenient().when(order.getPaymentMethod()).thenReturn("CARD");
        lenient().when(order.getShippingAddress()).thenReturn("서울시 강남구");
        lenient().when(order.getRecipientName()).thenReturn("홍길동");
        lenient().when(order.getRecipientPhone()).thenReturn("010-1234-5678");
        lenient().when(order.getItems()).thenReturn(Collections.emptyList());
        lenient().when(order.getOrderDate()).thenReturn(LocalDateTime.now());
        return order;
    }

    private IdempotencyRecord createRecord(Long recordId, String status) {
        IdempotencyRecord record = new IdempotencyRecord(USER_ID, VALID_KEY, "ORDER");
        ReflectionTestUtils.setField(record, "recordId", recordId);
        ReflectionTestUtils.setField(record, "status", status);
        return record;
    }

    private String validOrderJson() throws Exception {
        return """
                {
                    "shippingAddress": "서울시 강남구",
                    "recipientName": "홍길동",
                    "recipientPhone": "010-1234-5678",
                    "paymentMethod": "CARD"
                }
                """;
    }

    // ── 테스트 ─────────────────────────────────────────────────

    @Nested
    @DisplayName("멱등성 키 없이 요청 — 하위 호환")
    class WithoutIdempotencyKey {

        @Test
        @DisplayName("X-Idempotency-Key 헤더 없이 요청하면 기존 비멱등 동작으로 주문을 생성한다")
        void createsOrderWithoutIdempotency() throws Exception {
            Order order = createMockOrder(100L);
            when(orderService.createOrder(eq(USER_ID), any())).thenReturn(order);

            mockMvc.perform(post("/api/v1/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validOrderJson()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.orderId").value(100));

            verify(orderWriteIdempotencyGuard).handleMissingKey("api", "create", USER_ID);
            // IdempotencyService는 호출되지 않아야 한다
            verifyNoInteractions(idempotencyService);
        }
    }

    @Nested
    @DisplayName("최초 요청 — PROCESSING → 주문 생성 → COMPLETED")
    class FirstRequest {

        @Test
        @DisplayName("멱등성 키로 최초 요청 시 주문을 생성하고 201을 반환한다")
        void createsOrderAndMarksCompleted() throws Exception {
            IdempotencyRecord record = createRecord(10L, IdempotencyRecord.STATUS_PROCESSING);
            Order order = createMockOrder(100L);

            when(idempotencyService.findExisting(USER_ID, VALID_KEY)).thenReturn(Optional.empty());
            when(idempotencyService.initRecord(USER_ID, VALID_KEY, "ORDER")).thenReturn(record);
            when(orderService.createOrder(eq(USER_ID), any())).thenReturn(order);
            doAnswer(invocation -> {
                java.util.function.Supplier<Order> action = invocation.getArgument(1);
                return action.get();
            }).when(idempotencyService).executeWithCompletion(
                    eq(10L),
                    org.mockito.ArgumentMatchers.<java.util.function.Supplier<Order>>any(),
                    org.mockito.ArgumentMatchers.<java.util.function.Function<Order, Long>>any(),
                    eq(201));

            mockMvc.perform(post("/api/v1/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Idempotency-Key", VALID_KEY)
                            .content(validOrderJson()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.orderId").value(100));

            verify(idempotencyService).executeWithCompletion(
                    eq(10L),
                    org.mockito.ArgumentMatchers.<java.util.function.Supplier<Order>>any(),
                    org.mockito.ArgumentMatchers.<java.util.function.Function<Order, Long>>any(),
                    eq(201));
        }
    }

    @Nested
    @DisplayName("중복 요청 — COMPLETED 캐시 반환")
    class DuplicateCompleted {

        @Test
        @DisplayName("이전 성공 요청의 캐시된 응답을 반환하고 주문을 재생성하지 않는다")
        void returnsCachedResponse() throws Exception {
            IdempotencyRecord record = createRecord(10L, IdempotencyRecord.STATUS_COMPLETED);
            ReflectionTestUtils.setField(record, "resourceId", 100L);
            ReflectionTestUtils.setField(record, "httpStatus", 201);

            // 캐시된 응답 JSON을 설정
            String cachedJson = objectMapper.writeValueAsString(
                    com.shop.global.dto.ApiResponse.ok(null));
            ReflectionTestUtils.setField(record, "responseBody", cachedJson);

            when(idempotencyService.findExisting(USER_ID, VALID_KEY)).thenReturn(Optional.of(record));

            mockMvc.perform(post("/api/v1/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Idempotency-Key", VALID_KEY)
                            .content(validOrderJson()))
                    .andExpect(status().isCreated());

            // 주문 서비스는 호출되지 않아야 한다 — 캐시에서 반환
            verify(orderService, never()).createOrder(any(), any());
        }
    }

    @Nested
    @DisplayName("중복 요청 — PROCESSING 처리 중")
    class DuplicateProcessing {

        @Test
        @DisplayName("이전 요청이 처리 중이면 409 Conflict를 반환한다")
        void returns409WhenProcessing() throws Exception {
            IdempotencyRecord record = createRecord(10L, IdempotencyRecord.STATUS_PROCESSING);
            when(idempotencyService.findExisting(USER_ID, VALID_KEY)).thenReturn(Optional.of(record));

            mockMvc.perform(post("/api/v1/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Idempotency-Key", VALID_KEY)
                            .content(validOrderJson()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_PROCESSING"));

            verify(orderService, never()).createOrder(any(), any());
        }
    }

    @Nested
    @DisplayName("동시 INSERT 충돌 — UNIQUE 제약 위반")
    class ConcurrentInsertConflict {

        @Test
        @DisplayName("동시에 같은 키로 INSERT를 시도하면 409 Conflict를 반환한다")
        void returns409OnUniqueViolation() throws Exception {
            when(idempotencyService.findExisting(USER_ID, VALID_KEY)).thenReturn(Optional.empty());
            // initRecord가 UNIQUE 제약 위반을 발생시키는 경우
            when(idempotencyService.initRecord(USER_ID, VALID_KEY, "ORDER"))
                    .thenThrow(new DataIntegrityViolationException("uk_idempotency_user_key"));

            mockMvc.perform(post("/api/v1/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Idempotency-Key", VALID_KEY)
                            .content(validOrderJson()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));

            verify(orderService, never()).createOrder(any(), any());
        }
    }

    @Nested
    @DisplayName("주문 생성 실패 — FAILED 전환")
    class OrderCreationFailure {

        @Test
        @DisplayName("주문 생성 중 BusinessException 발생 시 FAILED로 전환한다")
        void marksFailedOnBusinessException() throws Exception {
            IdempotencyRecord record = createRecord(10L, IdempotencyRecord.STATUS_PROCESSING);

            when(idempotencyService.findExisting(USER_ID, VALID_KEY)).thenReturn(Optional.empty());
            when(idempotencyService.initRecord(USER_ID, VALID_KEY, "ORDER")).thenReturn(record);
            when(idempotencyService.executeWithCompletion(
                    eq(10L),
                    org.mockito.ArgumentMatchers.<java.util.function.Supplier<Order>>any(),
                    org.mockito.ArgumentMatchers.<java.util.function.Function<Order, Long>>any(),
                    eq(201)))
                    .thenThrow(new BusinessException("EMPTY_CART", "장바구니가 비어있습니다."));

            mockMvc.perform(post("/api/v1/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Idempotency-Key", VALID_KEY)
                            .content(validOrderJson()))
                    .andExpect(status().isBadRequest());

            // FAILED로 전환되었는지 검증
            verify(idempotencyService).markFailed(10L);
        }
    }

    @Nested
    @DisplayName("키 형식 검증")
    class KeyValidation {

        @Test
        @DisplayName("잘못된 형식의 키는 400 에러를 반환한다")
        void rejectsInvalidKey() throws Exception {
            // 특수문자가 포함된 키 — SQL injection 방어
            doThrow(new BusinessException("INVALID_IDEMPOTENCY_KEY", "멱등성 키 형식 오류"))
                    .when(idempotencyService).validateKey("key'; DROP TABLE--");

            mockMvc.perform(post("/api/v1/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Idempotency-Key", "key'; DROP TABLE--")
                            .content(validOrderJson()))
                    .andExpect(status().isBadRequest());

            verify(orderService, never()).createOrder(any(), any());
        }
    }
}
