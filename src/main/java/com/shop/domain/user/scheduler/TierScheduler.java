package com.shop.domain.user.scheduler;

import com.shop.domain.order.repository.OrderRepository;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.entity.UserTier;
import com.shop.domain.user.entity.UserTierHistory;
import com.shop.domain.user.repository.UserRepository;
import com.shop.domain.user.repository.UserTierHistoryRepository;
import com.shop.domain.user.repository.UserTierRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class TierScheduler {

    private static final Logger log = LoggerFactory.getLogger(TierScheduler.class);

    private final UserRepository userRepository;
    private final UserTierRepository userTierRepository;
    private final UserTierHistoryRepository tierHistoryRepository;
    private final OrderRepository orderRepository;

    public TierScheduler(UserRepository userRepository,
                         UserTierRepository userTierRepository,
                         UserTierHistoryRepository tierHistoryRepository,
                         OrderRepository orderRepository) {
        this.userRepository = userRepository;
        this.userTierRepository = userTierRepository;
        this.tierHistoryRepository = tierHistoryRepository;
        this.orderRepository = orderRepository;
    }

    /**
     * 매년 1월 1일 00:00:00 실행
     * 전년도 주문금액 기준으로 모든 회원의 등급을 재산정한다.
     */
    @Scheduled(cron = "0 0 0 1 1 *")
    @Transactional
    public void recalculateTiers() {
        int lastYear = Year.now().getValue() - 1;
        log.info("===== {}년도 실적 기준 등급 재산정 시작 =====", lastYear);

        LocalDateTime startDate = LocalDateTime.of(lastYear, 1, 1, 0, 0, 0);
        LocalDateTime endDate = LocalDateTime.of(lastYear + 1, 1, 1, 0, 0, 0);

        // 1) 전년도 사용자별 주문금액 집계 (취소 주문 제외)
        List<Object[]> yearlySpentList = orderRepository.findYearlySpentByUser(startDate, endDate);
        Map<Long, BigDecimal> yearlySpentMap = new HashMap<>();
        for (Object[] row : yearlySpentList) {
            Long userId = (Long) row[0];
            BigDecimal spent = (BigDecimal) row[1];
            yearlySpentMap.put(userId, spent);
        }

        // 2) 기본 등급 (웰컴) 조회
        UserTier defaultTier = userTierRepository.findByTierLevel(1)
                .orElseThrow(() -> new RuntimeException("기본 등급이 존재하지 않습니다."));

        // 3) 전체 회원 순회
        List<User> allUsers = userRepository.findAll();
        int upgraded = 0, downgraded = 0, unchanged = 0;

        for (User user : allUsers) {
            BigDecimal lastYearSpent = yearlySpentMap.getOrDefault(user.getUserId(), BigDecimal.ZERO);
            Integer oldTierId = user.getTier().getTierId();

            // 전년도 주문금액 기준으로 새 등급 결정
            UserTier newTier = userTierRepository
                    .findFirstByMinSpentLessThanEqualOrderByTierLevelDesc(lastYearSpent)
                    .orElse(defaultTier);

            // totalSpent를 전년도 실적으로 갱신
            user.setTotalSpent(lastYearSpent);

            if (!newTier.getTierId().equals(oldTierId)) {
                int oldLevel = user.getTier().getTierLevel();
                user.updateTier(newTier);

                // 등급 변경 이력 저장
                String reason = String.format("%d년 실적 재산정 (주문금액: %s원)", lastYear,
                        String.format("%,.0f", lastYearSpent));
                tierHistoryRepository.save(new UserTierHistory(
                        user.getUserId(), oldTierId, newTier.getTierId(), reason));

                if (newTier.getTierLevel() > oldLevel) {
                    upgraded++;
                } else {
                    downgraded++;
                }
                log.info("회원 {} (ID:{}): {} → {} (전년도 주문금액: {}원)",
                        user.getName(), user.getUserId(),
                        oldTierId, newTier.getTierName(),
                        String.format("%,.0f", lastYearSpent));
            } else {
                unchanged++;
            }
        }

        log.info("===== 등급 재산정 완료: 승급 {}명, 강등 {}명, 유지 {}명 (전체 {}명) =====",
                upgraded, downgraded, unchanged, allUsers.size());
    }
}
