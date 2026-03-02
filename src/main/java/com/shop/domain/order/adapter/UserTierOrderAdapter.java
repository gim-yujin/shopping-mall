package com.shop.domain.order.adapter;

import com.shop.domain.order.repository.OrderRepository;
import com.shop.domain.user.port.UserTierOrderPort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class UserTierOrderAdapter implements UserTierOrderPort {

    private final OrderRepository orderRepository;

    public UserTierOrderAdapter(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    public Map<Long, BigDecimal> findYearlySpentByUser(LocalDateTime startDate, LocalDateTime endDate) {
        List<Object[]> yearlySpentList = orderRepository.findYearlySpentByUser(startDate, endDate);

        Map<Long, BigDecimal> yearlySpentMap = new HashMap<>();
        for (Object[] row : yearlySpentList) {
            yearlySpentMap.put((Long) row[0], (BigDecimal) row[1]);
        }
        return yearlySpentMap;
    }
}
