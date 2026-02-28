package com.shop.domain.point.service;

import com.shop.domain.point.entity.PointHistory;
import com.shop.domain.point.repository.PointHistoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PointQueryServiceUnitTest {

    @Mock
    private PointHistoryRepository pointHistoryRepository;

    @InjectMocks
    private PointQueryService pointQueryService;

    @Test
    @DisplayName("운영용 포인트 조회 - 기간/유형 필터를 정규화해서 조회한다")
    void getPointHistoriesForOps_withFilters() {
        LocalDate fromDate = LocalDate.of(2026, 1, 1);
        LocalDate toDate = LocalDate.of(2026, 1, 31);
        PageRequest pageRequest = PageRequest.of(0, 20);

        when(pointHistoryRepository.findForOps(
                eq(LocalDateTime.of(2026, 1, 1, 0, 0)),
                eq(LocalDateTime.of(2026, 2, 1, 0, 0)),
                eq(PointHistory.EARN),
                eq(pageRequest)
        )).thenReturn(new PageImpl<>(List.of()));

        pointQueryService.getPointHistoriesForOps(fromDate, toDate, "earn", pageRequest);

        verify(pointHistoryRepository).findForOps(
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 2, 1, 0, 0),
                PointHistory.EARN,
                pageRequest
        );
    }

    @Test
    @DisplayName("운영용 포인트 조회 - 허용되지 않은 유형이면 전체 유형으로 조회한다")
    void getPointHistoriesForOps_invalidTypeUsesAll() {
        PageRequest pageRequest = PageRequest.of(0, 20);
        when(pointHistoryRepository.findForOps(null, null, null, pageRequest))
                .thenReturn(new PageImpl<>(List.of()));

        pointQueryService.getPointHistoriesForOps(null, null, "adjust", pageRequest);

        verify(pointHistoryRepository).findForOps(null, null, null, pageRequest);
    }
}
