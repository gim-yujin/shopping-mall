package com.shop.domain.user.repository;

import com.shop.domain.user.entity.UserTierHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface UserTierHistoryRepository extends JpaRepository<UserTierHistory, Long> {
    Page<UserTierHistory> findByUserIdOrderByChangedAtDesc(Long userId, Pageable pageable);
}
