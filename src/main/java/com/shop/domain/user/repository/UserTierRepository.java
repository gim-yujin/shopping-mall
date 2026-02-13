package com.shop.domain.user.repository;

import com.shop.domain.user.entity.UserTier;
import org.springframework.data.jpa.repository.JpaRepository;
import java.math.BigDecimal;
import java.util.Optional;

public interface UserTierRepository extends JpaRepository<UserTier, Integer> {
    Optional<UserTier> findByTierLevel(int level);
    Optional<UserTier> findFirstByMinSpentLessThanEqualOrderByTierLevelDesc(BigDecimal spent);
}
