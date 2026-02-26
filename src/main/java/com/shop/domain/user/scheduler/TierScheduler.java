package com.shop.domain.user.scheduler;

import com.shop.domain.order.repository.OrderRepository;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.entity.UserTier;
import com.shop.domain.user.entity.UserTierHistory;
import com.shop.domain.user.repository.UserRepository;
import com.shop.domain.user.repository.UserTierHistoryRepository;
import com.shop.domain.user.repository.UserTierRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class TierScheduler {

    private static final Logger log = LoggerFactory.getLogger(TierScheduler.class);
    private static final int DEFAULT_USER_CHUNK_SIZE = 1_000;

    private final UserRepository userRepository;
    private final UserTierRepository userTierRepository;
    private final UserTierHistoryRepository tierHistoryRepository;
    private final OrderRepository orderRepository;
    private final EntityManager entityManager;
    private final int userChunkSize;

    public TierScheduler(UserRepository userRepository,
                         UserTierRepository userTierRepository,
                         UserTierHistoryRepository tierHistoryRepository,
                         OrderRepository orderRepository,
                         EntityManager entityManager) {
        this(userRepository, userTierRepository, tierHistoryRepository, orderRepository, entityManager, DEFAULT_USER_CHUNK_SIZE);
    }

    public TierScheduler(UserRepository userRepository,
                         UserTierRepository userTierRepository,
                         UserTierHistoryRepository tierHistoryRepository,
                         OrderRepository orderRepository,
                         EntityManager entityManager,
                         int userChunkSize) {
        this.userRepository = userRepository;
        this.userTierRepository = userTierRepository;
        this.tierHistoryRepository = tierHistoryRepository;
        this.orderRepository = orderRepository;
        this.entityManager = entityManager;
        this.userChunkSize = userChunkSize;
    }

    /**
     * 매년 1월 1일 00:00:00 실행
     * 전년도 주문금액 기준으로 모든 회원의 등급을 재산정한다.
     */
    @Scheduled(cron = "0 0 0 1 1 *")
    @Transactional
    public void recalculateTiers() {
        int lastYear = Year.now().getValue() - 1;
        LocalDateTime startedAt = LocalDateTime.now();
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

        TierProcessingResult totalResult = new TierProcessingResult();
        int pageNumber = 0;

        while (true) {
            Pageable pageable = PageRequest.of(pageNumber, userChunkSize);
            Page<User> userPage = loadUserChunk(pageable);
            if (!userPage.hasContent()) {
                break;
            }

            long chunkStartedAt = System.nanoTime();
            TierProcessingResult chunkResult = processTierChunk(lastYear, yearlySpentMap, defaultTier, userPage.getContent());
            long chunkElapsedMs = Duration.ofNanos(System.nanoTime() - chunkStartedAt).toMillis();

            totalResult.merge(chunkResult);
            log.info("등급 재산정 청크 완료 - page={}, chunkSize={}, processed={}, upgraded={}, downgraded={}, unchanged={}, errors={}, elapsedMs={}",
                    pageNumber,
                    userChunkSize,
                    chunkResult.processed,
                    chunkResult.upgraded,
                    chunkResult.downgraded,
                    chunkResult.unchanged,
                    chunkResult.errors,
                    chunkElapsedMs);

            if (!userPage.hasNext()) {
                break;
            }
            pageNumber++;
        }

        long totalElapsedMs = Duration.between(startedAt, LocalDateTime.now()).toMillis();
        log.info("===== 등급 재산정 완료: processed={}, 승급 {}명, 강등 {}명, 유지 {}명, errors={}, elapsedMs={} =====",
                totalResult.processed,
                totalResult.upgraded,
                totalResult.downgraded,
                totalResult.unchanged,
                totalResult.errors,
                totalElapsedMs);
    }

    /**
     * 사용자 스캔 전략 교체 포인트 (예: ID 범위 배치 조회 방식).
     */
    protected Page<User> loadUserChunk(Pageable pageable) {
        return userRepository.findAll(pageable);
    }

    protected TierProcessingResult processTierChunk(int lastYear,
                                                    Map<Long, BigDecimal> yearlySpentMap,
                                                    UserTier defaultTier,
                                                    List<User> users) {
        TierProcessingResult result = new TierProcessingResult();

        for (User user : users) {
            result.processed++;

            try {
                BigDecimal lastYearSpent = yearlySpentMap.getOrDefault(user.getUserId(), BigDecimal.ZERO);
                Integer oldTierId = user.getTier().getTierId();

                UserTier newTier = userTierRepository
                        .findFirstByMinSpentLessThanEqualOrderByTierLevelDesc(lastYearSpent)
                        .orElse(defaultTier);

                user.setTotalSpent(lastYearSpent);

                if (!newTier.getTierId().equals(oldTierId)) {
                    int oldLevel = user.getTier().getTierLevel();
                    user.updateTier(newTier);

                    String reason = String.format("%d년 실적 재산정 (주문금액: %s원)", lastYear,
                            String.format("%,.0f", lastYearSpent));
                    tierHistoryRepository.save(new UserTierHistory(
                            user.getUserId(), oldTierId, newTier.getTierId(), reason));

                    if (newTier.getTierLevel() > oldLevel) {
                        result.upgraded++;
                    } else {
                        result.downgraded++;
                    }
                } else {
                    result.unchanged++;
                }
            } catch (Exception e) {
                result.errors++;
                log.error("회원 등급 재산정 실패 - userId={}", user.getUserId(), e);
            }
        }

        entityManager.flush();
        entityManager.clear();
        return result;
    }

    private static class TierProcessingResult {
        private int processed;
        private int upgraded;
        private int downgraded;
        private int unchanged;
        private int errors;

        private void merge(TierProcessingResult result) {
            this.processed += result.processed;
            this.upgraded += result.upgraded;
            this.downgraded += result.downgraded;
            this.unchanged += result.unchanged;
            this.errors += result.errors;
        }
    }
}
