package com.shop.domain.user.repository;

import com.shop.domain.user.entity.UserTier;
import org.springframework.data.jpa.repository.JpaRepository;
import java.math.BigDecimal;
import java.util.Optional;

public interface UserTierRepository extends JpaRepository<UserTier, Integer> {
    Optional<UserTier> findByTierLevel(int level);
    Optional<UserTier> findFirstByMinSpentLessThanEqualOrderByTierLevelDesc(BigDecimal spent);

    /**
     * [Phase 20] 전체 등급을 minSpent 내림차순으로 조회한다.
     * TierScheduler에서 사용자별 등급 결정 시 in-memory 매칭에 사용된다.
     * 등급 수가 소수(4~5개)이므로 전체 로딩 후 재사용이 효율적이다.
     */
    java.util.List<UserTier> findAllByOrderByMinSpentDesc();
}
