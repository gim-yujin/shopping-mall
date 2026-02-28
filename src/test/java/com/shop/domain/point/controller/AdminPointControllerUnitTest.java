package com.shop.domain.point.controller;

import com.shop.domain.point.entity.PointHistory;
import com.shop.domain.point.service.PointQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@ExtendWith(MockitoExtension.class)
class AdminPointControllerUnitTest {

    @Mock
    private PointQueryService pointQueryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AdminPointController controller = new AdminPointController(pointQueryService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("관리자 포인트 이력 조회 - 기간/유형 필터와 함께 화면을 반환한다")
    void pointHistories_returnsFilteredView() throws Exception {
        PointHistory history = new PointHistory(1L, PointHistory.EARN, 1000, 5000, "ORDER", 1L, "주문 적립");
        when(pointQueryService.getPointHistoriesForOps(
                eq(LocalDate.of(2026, 1, 1)),
                eq(LocalDate.of(2026, 1, 31)),
                eq("EARN"),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(history), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/admin/points")
                        .param("page", "0")
                        .param("fromDate", "2026-01-01")
                        .param("toDate", "2026-01-31")
                        .param("changeType", "EARN"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/points"))
                .andExpect(model().attributeExists("pointHistories"))
                .andExpect(model().attribute("currentChangeType", "EARN"));

        verify(pointQueryService).getPointHistoriesForOps(
                eq(LocalDate.of(2026, 1, 1)),
                eq(LocalDate.of(2026, 1, 31)),
                eq("EARN"),
                any(Pageable.class)
        );
    }
}
