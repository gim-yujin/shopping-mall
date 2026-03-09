package com.shop.domain.order.controller.api;

import com.shop.domain.order.dto.OrderCreateRequest;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.service.OrderService;
import com.shop.global.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        OrderApiController controller = new OrderApiController(orderService);

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

        @Test
        @DisplayName("정상 조회 시 페이징된 주문 목록을 반환한다")
        void getOrders_returnsPagedList() throws Exception {
            // given: 주문 1건이 존재하는 첫 페이지
            Order order = createOrder();
            Page<Order> page = new PageImpl<>(List.of(order), PageRequest.of(0, 10), 1);
            when(orderService.getOrdersByUser(eq(USER_ID), any(PageRequest.class))).thenReturn(page);

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
            // given: 주문 없음
            Page<Order> page = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 10), 0);
            when(orderService.getOrdersByUser(eq(USER_ID), any(PageRequest.class))).thenReturn(page);

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
