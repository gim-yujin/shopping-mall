package com.shop.domain.flashsale.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.domain.flashsale.dto.FlashSaleDetailResponse;
import com.shop.domain.flashsale.dto.FlashSaleListItemResponse;
import com.shop.domain.flashsale.dto.FlashSalePurchaseResponse;
import com.shop.domain.flashsale.entity.FlashSaleStatus;
import com.shop.domain.flashsale.service.FlashSaleCommandService;
import com.shop.domain.flashsale.service.FlashSaleQueryService;
import com.shop.global.dto.ApiResponse;
import com.shop.global.idempotency.IdempotencyExecutor;
import com.shop.global.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class FlashSaleApiControllerUnitTest {

    private static final long USER_ID = 7L;

    @Mock
    private FlashSaleQueryService flashSaleQueryService;

    @Mock
    private FlashSaleCommandService flashSaleCommandService;

    @Mock
    private IdempotencyExecutor idempotencyExecutor;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper om = new ObjectMapper().findAndRegisterModules();
        FlashSaleApiController controller = new FlashSaleApiController(
                flashSaleQueryService, flashSaleCommandService, idempotencyExecutor, om);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();

        CustomUserPrincipal principal = new CustomUserPrincipal(
                USER_ID, "buyer", "encoded", "구매자", "ROLE_USER",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET /api/v1/flash-sales — 목록을 ApiResponse로 감싸 반환")
    void list_returnsWrappedList() throws Exception {
        when(flashSaleQueryService.listActiveAndUpcoming()).thenReturn(List.of(
                new FlashSaleListItemResponse(
                        1L, "오전 특가", FlashSaleStatus.ACTIVE,
                        LocalDateTime.now(), LocalDateTime.now().plusHours(1),
                        List.of(new FlashSaleListItemResponse.Item(
                                10L, 100L, "상품A", new BigDecimal("9900"),
                                new BigDecimal("19900"), 50, 100, 1))
                )
        ));

        mockMvc.perform(get("/api/v1/flash-sales"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].flashSaleId").value(1))
                .andExpect(jsonPath("$.data[0].items[0].productName").value("상품A"));
    }

    @Test
    @DisplayName("GET /api/v1/flash-sales/{id} — 상세를 ApiResponse로 반환")
    void detail_returnsWrappedDetail() throws Exception {
        when(flashSaleQueryService.getDetail(42L)).thenReturn(new FlashSaleDetailResponse(
                42L, "상세", FlashSaleStatus.ACTIVE,
                LocalDateTime.now(), LocalDateTime.now().plusHours(1),
                List.of()
        ));

        mockMvc.perform(get("/api/v1/flash-sales/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.flashSaleId").value(42))
                .andExpect(jsonPath("$.data.title").value("상세"));
    }

    @Test
    @DisplayName("POST /purchase — IdempotencyExecutor 경유해 201과 응답 본문 반환")
    void purchase_returns201() throws Exception {
        FlashSalePurchaseResponse resp = new FlashSalePurchaseResponse(
                500L, "2026-04-25-ABC", 10L, "상품A",
                new BigDecimal("19900"), 1, new BigDecimal("19900"));
        when(idempotencyExecutor.execute(eq(USER_ID), anyString(), eq("FLASH_SALE"), anyInt(),
                any(), any(), any(), any()))
                .thenReturn(ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(resp)));

        mockMvc.perform(post("/api/v1/flash-sales/1/items/10/purchase")
                        .header("X-Idempotency-Key", "11111111-2222-3333-4444-555555555555"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderId").value(500))
                .andExpect(jsonPath("$.data.productName").value("상품A"));
    }

    @Test
    @DisplayName("POST /purchase — X-Idempotency-Key 헤더 누락 시 400을 반환한다")
    void purchase_missingIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/flash-sales/1/items/10/purchase"))
                .andExpect(status().isBadRequest());
    }
}
