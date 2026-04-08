package com.shop.domain.inventory.controller.api;

import com.shop.domain.inventory.entity.ProductInventoryHistory;
import com.shop.domain.inventory.service.InventoryService;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * InventoryApiController 단위 테스트.
 *
 * <p>재고 관리 REST API의 2개 엔드포인트를 검증한다:
 * GET /{productId}/history(재고 변경 이력), POST /{productId}/adjust(수동 재고 조정).
 * 관리자 전용 API이므로 ROLE_ADMIN 인증 컨텍스트를 설정한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class InventoryApiControllerUnitTest {

    private static final Long ADMIN_USER_ID = 99L;

    @Mock
    private InventoryService inventoryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        InventoryApiController controller = new InventoryApiController(inventoryService);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setValidator(validator)
                .build();

        CustomUserPrincipal principal = new CustomUserPrincipal(
                ADMIN_USER_ID, "admin", "encoded", "관리자", "ROLE_ADMIN",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── GET /api/v1/admin/inventory/{productId}/history ────────

    @Nested
    @DisplayName("GET /{productId}/history — 재고 변경 이력 조회")
    class GetHistoryTests {

        @Test
        @DisplayName("재고 이력이 있으면 페이징된 목록을 반환한다")
        void getHistory_withRecords_returnsPagedResponse() throws Exception {
            ProductInventoryHistory h1 = new ProductInventoryHistory(
                    10L, "INBOUND", 100, 0, 100, "초기 입고", null, ADMIN_USER_ID);
            ProductInventoryHistory h2 = new ProductInventoryHistory(
                    10L, "SOLD", -2, 100, 98, "주문 판매", 500L, null);
            Page<ProductInventoryHistory> page = new PageImpl<>(
                    List.of(h1, h2), PageRequest.of(0, 10), 2);

            when(inventoryService.getHistory(eq(10L), any(PageRequest.class))).thenReturn(page);

            mockMvc.perform(get("/api/v1/admin/inventory/10/history"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content.length()").value(2))
                    .andExpect(jsonPath("$.data.content[0].changeType").value("INBOUND"))
                    .andExpect(jsonPath("$.data.content[0].changeAmount").value(100))
                    .andExpect(jsonPath("$.data.content[0].afterQuantity").value(100))
                    .andExpect(jsonPath("$.data.content[1].changeType").value("SOLD"))
                    .andExpect(jsonPath("$.data.totalElements").value(2));
        }

        @Test
        @DisplayName("재고 이력이 없으면 빈 목록을 반환한다")
        void getHistory_empty_returnsEmptyPage() throws Exception {
            Page<ProductInventoryHistory> emptyPage = new PageImpl<>(
                    Collections.emptyList(), PageRequest.of(0, 10), 0);

            when(inventoryService.getHistory(eq(10L), any(PageRequest.class))).thenReturn(emptyPage);

            mockMvc.perform(get("/api/v1/admin/inventory/10/history"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isEmpty())
                    .andExpect(jsonPath("$.data.totalElements").value(0));
        }

        @Test
        @DisplayName("페이지 파라미터를 전달하면 정규화되어 서비스에 전달된다")
        void getHistory_withPageParam_normalizedAndPassed() throws Exception {
            Page<ProductInventoryHistory> page = new PageImpl<>(
                    Collections.emptyList(), PageRequest.of(3, 10), 0);

            when(inventoryService.getHistory(eq(10L), eq(PageRequest.of(3, 10)))).thenReturn(page);

            mockMvc.perform(get("/api/v1/admin/inventory/10/history").param("page", "3"))
                    .andExpect(status().isOk());

            verify(inventoryService).getHistory(eq(10L), eq(PageRequest.of(3, 10)));
        }
    }

    // ── POST /api/v1/admin/inventory/{productId}/adjust ────────

    @Nested
    @DisplayName("POST /{productId}/adjust — 수동 재고 조정")
    class AdjustStockTests {

        @Test
        @DisplayName("양수 수량으로 입고 조정 성공")
        void adjustStock_positiveAmount_success() throws Exception {
            mockMvc.perform(post("/api/v1/admin/inventory/10/adjust")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\": 50, \"reason\": \"추가 입고\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(inventoryService).adjustStock(10L, 50, "추가 입고", ADMIN_USER_ID);
        }

        @Test
        @DisplayName("음수 수량으로 출고 조정 성공")
        void adjustStock_negativeAmount_success() throws Exception {
            mockMvc.perform(post("/api/v1/admin/inventory/10/adjust")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\": -10, \"reason\": \"불량 폐기\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(inventoryService).adjustStock(10L, -10, "불량 폐기", ADMIN_USER_ID);
        }

        @Test
        @DisplayName("수량 누락 시 400 에러를 반환한다")
        void adjustStock_nullAmount_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/admin/inventory/10/adjust")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\": \"사유만 있음\"}"))
                    .andExpect(status().isBadRequest());

            verify(inventoryService, never()).adjustStock(anyLong(), anyInt(), anyString(), anyLong());
        }

        @Test
        @DisplayName("사유 누락 시 400 에러를 반환한다")
        void adjustStock_blankReason_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/admin/inventory/10/adjust")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\": 10, \"reason\": \"\"}"))
                    .andExpect(status().isBadRequest());

            verify(inventoryService, never()).adjustStock(anyLong(), anyInt(), anyString(), anyLong());
        }
    }
}
