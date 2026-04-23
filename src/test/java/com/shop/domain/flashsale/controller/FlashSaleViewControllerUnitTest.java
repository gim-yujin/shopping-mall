package com.shop.domain.flashsale.controller;

import com.shop.domain.flashsale.dto.FlashSaleDetailResponse;
import com.shop.domain.flashsale.dto.FlashSaleListItemResponse;
import com.shop.domain.flashsale.entity.FlashSaleStatus;
import com.shop.domain.flashsale.service.FlashSaleQueryService;
import com.shop.global.exception.ResourceNotFoundException;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@ExtendWith(MockitoExtension.class)
class FlashSaleViewControllerUnitTest {

    @Mock
    private FlashSaleQueryService flashSaleQueryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        FlashSaleViewController controller = new FlashSaleViewController(flashSaleQueryService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("/flash-sales — 목록 뷰 이름과 모델 속성을 채워 반환한다")
    void list_rendersListView() throws Exception {
        when(flashSaleQueryService.listActiveAndUpcoming()).thenReturn(List.of(
                new FlashSaleListItemResponse(
                        1L, "오늘 특가", FlashSaleStatus.ACTIVE,
                        LocalDateTime.now(), LocalDateTime.now().plusHours(1),
                        List.of(new FlashSaleListItemResponse.Item(
                                10L, 100L, "상품", new BigDecimal("9900"),
                                new BigDecimal("19900"), 50, 100, 1))
                )
        ));

        mockMvc.perform(get("/flash-sales"))
                .andExpect(status().isOk())
                .andExpect(view().name("flashsale/list"))
                .andExpect(model().attributeExists("flashSales"))
                .andExpect(model().attributeExists("now"));
    }

    @Test
    @DisplayName("/flash-sales/{id} — 상세 뷰 이름과 모델 속성을 채워 반환한다")
    void detail_rendersDetailView() throws Exception {
        when(flashSaleQueryService.getDetail(42L)).thenReturn(new FlashSaleDetailResponse(
                42L, "상세", FlashSaleStatus.ACTIVE,
                LocalDateTime.now(), LocalDateTime.now().plusHours(1),
                List.of()
        ));

        mockMvc.perform(get("/flash-sales/42"))
                .andExpect(status().isOk())
                .andExpect(view().name("flashsale/detail"))
                .andExpect(model().attributeExists("flashSale"));
    }

    @Test
    @DisplayName("존재하지 않는 세일 ID로 상세 조회 시 ResourceNotFoundException이 전파된다")
    void detail_propagatesNotFound() {
        when(flashSaleQueryService.getDetail(999L))
                .thenThrow(new ResourceNotFoundException("플래시 세일", 999L));

        assertThatThrownBy(() -> mockMvc.perform(get("/flash-sales/999")))
                .isInstanceOf(ServletException.class)
                .hasCauseInstanceOf(ResourceNotFoundException.class);
    }
}
