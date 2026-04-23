package com.shop.domain.flashsale.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.domain.flashsale.dto.FlashSaleDetailResponse;
import com.shop.domain.flashsale.dto.FlashSaleListItemResponse;
import com.shop.domain.flashsale.entity.FlashSaleStatus;
import com.shop.domain.flashsale.service.FlashSaleQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class FlashSaleApiControllerUnitTest {

    @Mock
    private FlashSaleQueryService flashSaleQueryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        FlashSaleApiController controller = new FlashSaleApiController(flashSaleQueryService);
        ObjectMapper om = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
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
}
