package com.shop.domain.point.service;

import com.shop.domain.point.entity.PointHistory;
import com.shop.domain.point.repository.PointHistoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class PointQueryService {

    private static final Set<String> OPS_ALLOWED_CHANGE_TYPES = Set.of(
            PointHistory.EARN,
            PointHistory.USE,
            PointHistory.REFUND
    );

    private final PointHistoryRepository pointHistoryRepository;

    public PointQueryService(PointHistoryRepository pointHistoryRepository) {
        this.pointHistoryRepository = pointHistoryRepository;
    }

    public Page<PointHistory> getPointHistoriesByUser(Long userId, Pageable pageable) {
        return pointHistoryRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    public Page<PointHistory> getPointHistoriesForOps(LocalDate fromDate, LocalDate toDate,
                                                      String changeType, Pageable pageable) {
        LocalDateTime fromDateTime = fromDate != null ? fromDate.atStartOfDay() : null;
        LocalDateTime toDateTime = toDate != null ? toDate.plusDays(1).atStartOfDay() : null;
        String normalizedType = normalizeOpsChangeType(changeType);
        return pointHistoryRepository.findForOps(fromDateTime, toDateTime, normalizedType, pageable);
    }

    private String normalizeOpsChangeType(String changeType) {
        if (changeType == null || changeType.isBlank()) {
            return null;
        }
        String normalizedType = changeType.trim().toUpperCase();
        return OPS_ALLOWED_CHANGE_TYPES.contains(normalizedType) ? normalizedType : null;
    }
}
