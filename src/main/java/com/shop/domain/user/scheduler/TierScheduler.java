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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate txTemplate;
    private final TransactionTemplate txReadOnlyTemplate;
    private final int userChunkSize;

    @Autowired
    public TierScheduler(UserRepository userRepository,
                         UserTierRepository userTierRepository,
                         UserTierHistoryRepository tierHistoryRepository,
                         OrderRepository orderRepository,
                         EntityManager entityManager,
                         PlatformTransactionManager txManager) {
        this(userRepository, userTierRepository, tierHistoryRepository, orderRepository,
                entityManager, txManager, DEFAULT_USER_CHUNK_SIZE);
    }

    public TierScheduler(UserRepository userRepository,
                         UserTierRepository userTierRepository,
                         UserTierHistoryRepository tierHistoryRepository,
                         OrderRepository orderRepository,
                         EntityManager entityManager,
                         PlatformTransactionManager txManager,
                         int userChunkSize) {
        this.userRepository = userRepository;
        this.userTierRepository = userTierRepository;
        this.tierHistoryRepository = tierHistoryRepository;
        this.orderRepository = orderRepository;
        this.entityManager = entityManager;
        this.userChunkSize = userChunkSize;

        this.txTemplate = new TransactionTemplate(txManager);
        this.txReadOnlyTemplate = new TransactionTemplate(txManager);
        this.txReadOnlyTemplate.setReadOnly(true);
    }

    /**
     * 매년 1월 1일 00:00:00 실행
     * 전년도 주문금액 기준으로 모든 회원의 등급을 재산정한다.
     *
     * 트랜잭션 전략: 메서드 전체를 하나의 트랜잭션으로 묶지 않고,
     * 집계 조회(읽기 전용)와 청크별 갱신을 각각 독립 트랜잭션으로 실행한다.
     * → 100만 명 처리 시에도 커넥션 장시간 점유·전체 롤백 위험 없음.
     */
    @Scheduled(cron = "0 0 0 1 1 *")
    public void recalculateTiers() {
        int lastYear = Year.now().getValue() - 1;
        LocalDateTime startedAt = LocalDateTime.now();
        log.info("===== {}년도 실적 기준 등급 재산정 시작 =====", lastYear);

        LocalDateTime startDate = LocalDateTime.of(lastYear, 1, 1, 0, 0, 0);
        LocalDateTime endDate = LocalDateTime.of(lastYear + 1, 1, 1, 0, 0, 0);

        // 1) 전년도 사용자별 주문금액 집계 (취소 주문 제외) — 읽기 전용 트랜잭션
        Map<Long, BigDecimal> yearlySpentMap = txReadOnlyTemplate.execute(status -> {
            List<Object[]> yearlySpentList = orderRepository.findYearlySpentByUser(startDate, endDate);
            Map<Long, BigDecimal> map = new HashMap<>();
            for (Object[] row : yearlySpentList) {
                map.put((Long) row[0], (BigDecimal) row[1]);
            }
            return map;
        });

        // 2) 기본 등급 (웰컴) 조회 — 읽기 전용 트랜잭션
        UserTier defaultTier = txReadOnlyTemplate.execute(status ->
                userTierRepository.findByTierLevel(1)
                        .orElseThrow(() -> new RuntimeException("기본 등급이 존재하지 않습니다.")));

        TierProcessingResult totalResult = new TierProcessingResult();
        int pageNumber = 0;

        while (true) {
            Pageable pageable = PageRequest.of(pageNumber, userChunkSize);

            // 3) 사용자 청크 로드 — 읽기 전용 트랜잭션
            Page<User> userPage = txReadOnlyTemplate.execute(status -> loadUserChunk(pageable));
            if (userPage == null || !userPage.hasContent()) {
                break;
            }

            // 4) 청크별 등급 갱신 — 독립 쓰기 트랜잭션 (실패 시 해당 청크만 롤백)
            long chunkStartedAt = System.nanoTime();
            boolean hasNext = userPage.hasNext();
            List<User> users = userPage.getContent();

            TierProcessingResult chunkResult = txTemplate.execute(status -> {
                try {
                    return processTierChunk(lastYear, yearlySpentMap, defaultTier, users);
                } catch (Exception e) {
                    status.setRollbackOnly();
                    log.error("등급 재산정 청크 실패 - page={}", pageable.getPageNumber(), e);
                    TierProcessingResult errorResult = new TierProcessingResult();
                    errorResult.errors = users.size();
                    errorResult.processed = users.size();
                    return errorResult;
                }
            });

            long chunkElapsedMs = Duration.ofNanos(System.nanoTime() - chunkStartedAt).toMillis();

            if (chunkResult != null) {
                totalResult.merge(chunkResult);
            }
            log.info("등급 재산정 청크 완료 - page={}, chunkSize={}, processed={}, upgraded={}, downgraded={}, unchanged={}, errors={}, elapsedMs={}",
                    pageNumber,
                    userChunkSize,
                    chunkResult != null ? chunkResult.processed : 0,
                    chunkResult != null ? chunkResult.upgraded : 0,
                    chunkResult != null ? chunkResult.downgraded : 0,
                    chunkResult != null ? chunkResult.unchanged : 0,
                    chunkResult != null ? chunkResult.errors : 0,
                    chunkElapsedMs);

            if (!hasNext) {
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
