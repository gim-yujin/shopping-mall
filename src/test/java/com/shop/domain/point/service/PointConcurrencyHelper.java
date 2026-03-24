package com.shop.domain.point.service;

import com.shop.domain.user.entity.User;
import com.shop.domain.user.repository.UserRepository;
import com.shop.global.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포인트 동시성 테스트 전용 헬퍼.
 *
 * 실 운영 코드에서 포인트 변경은 OrderCreationService / OrderCancellationService 등에서
 * findByIdWithLockAndTier()로 비관적 잠금을 잡은 뒤 User.addPoints() / usePoints()를 호출한다.
 * 이 헬퍼는 동일한 잠금 경로를 재현하여, 비관적 잠금이 동시성을 올바르게 직렬화하는지 검증한다.
 */
@Service
public class PointConcurrencyHelper {

    private final UserRepository userRepository;

    public PointConcurrencyHelper(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public void usePointsWithLock(Long userId, int points) {
        User user = userRepository.findByIdWithLockAndTier(userId)
                .orElseThrow(() -> new ResourceNotFoundException("사용자", userId));
        user.usePoints(points);
    }

    @Transactional
    public void addPointsWithLock(Long userId, int points) {
        User user = userRepository.findByIdWithLockAndTier(userId)
                .orElseThrow(() -> new ResourceNotFoundException("사용자", userId));
        user.addPoints(points);
    }
}
