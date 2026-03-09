package com.shop.domain.order.adapter;

import com.shop.domain.order.repository.OrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * UserTierOrderAdapter 단위 테스트.
 *
 * <p>이 어댑터는 OrderRepository.findYearlySpentByUser()의 Object[] 결과를
 * Map&lt;Long, BigDecimal&gt;으로 변환하는 역할을 한다.
 * 변환 로직(Object[]→Map 매핑)이 올바르게 동작하는지 검증한다.</p>
 *
 * <p>커버리지 목표: 15% → 100% (9라인 전체)</p>
 */
@ExtendWith(MockitoExtension.class)
class UserTierOrderAdapterUnitTest {

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private UserTierOrderAdapter adapter;

    @Test
    @DisplayName("Object[] 결과를 Map<Long, BigDecimal>으로 올바르게 변환한다")
    void findYearlySpentByUser_convertsToMap() {
        // given: 유저 2명의 연간 누적 구매금액
        LocalDateTime start = LocalDateTime.of(2025, 1, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2025, 12, 31, 23, 59);

        // OrderRepository가 반환하는 Object[] 형태: [userId(Long), totalSpent(BigDecimal)]
        List<Object[]> rawResult = List.of(
                new Object[]{1L, new BigDecimal("150000")},
                new Object[]{2L, new BigDecimal("320000")}
        );
        when(orderRepository.findYearlySpentByUser(start, end)).thenReturn(rawResult);

        // when
        Map<Long, BigDecimal> result = adapter.findYearlySpentByUser(start, end);

        // then: Object[]이 Key-Value 쌍으로 올바르게 변환
        assertThat(result).hasSize(2);
        assertThat(result.get(1L)).isEqualByComparingTo("150000");
        assertThat(result.get(2L)).isEqualByComparingTo("320000");
    }

    @Test
    @DisplayName("결과가 없으면 빈 맵을 반환한다")
    void findYearlySpentByUser_emptyResult_returnsEmptyMap() {
        // given: 해당 기간 주문 없음
        LocalDateTime start = LocalDateTime.of(2025, 1, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2025, 12, 31, 23, 59);

        when(orderRepository.findYearlySpentByUser(start, end)).thenReturn(Collections.emptyList());

        // when
        Map<Long, BigDecimal> result = adapter.findYearlySpentByUser(start, end);

        // then
        assertThat(result).isEmpty();
    }
}
