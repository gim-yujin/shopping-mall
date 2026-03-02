package com.shop.domain.user.port;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public interface UserTierOrderPort {

    Map<Long, BigDecimal> findYearlySpentByUser(LocalDateTime startDate, LocalDateTime endDate);
}
