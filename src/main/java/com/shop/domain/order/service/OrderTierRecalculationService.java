package com.shop.domain.order.service;

import com.shop.domain.user.entity.User;
import com.shop.domain.user.repository.UserRepository;
import com.shop.domain.user.repository.UserTierRepository;
import com.shop.global.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * [Phase 6] 주문/취소 후 사용자 등급 재계산을 별도 트랜잭션에서 수행하는 서비스.
 *
 * <p><b>문제:</b> 등급 재계산이 주문/취소 트랜잭션 안에서 동기 실행되면,
 * User 엔티티에 대한 PESSIMISTIC_WRITE 락 보유 시간이
 * UserTier 조회 쿼리만큼 늘어난다. 동시 주문이 100건일 때
 * 이 추가 락 시간이 직렬화 병목이 된다.</p>
 *
 * <p><b>해결:</b> REQUIRES_NEW 전파로 독립 트랜잭션을 열어 User를 재조회하고 등급을 갱신한다.
 * 주문 트랜잭션은 totalSpent만 갱신하고 즉시 커밋하며,
 * 등급 반영은 수 밀리초 후 비동기로 완료된다.
 * 최악의 경우(비동기 실패), TierScheduler가 정기적으로 보정한다.</p>
 *
 * <p><b>왜 REQUIRES_NEW인가?</b> 이 서비스는 @Async 이벤트 리스너에서 호출된다.
 * 호출 시점에는 원본 주문 트랜잭션이 이미 커밋된 상태(AFTER_COMMIT)이므로
 * 활성 트랜잭션이 없다. REQUIRES_NEW로 명시하여 항상 새 트랜잭션을 생성하고,
 * 등급 갱신의 원자성을 보장한다.</p>
 */
@Service
public class OrderTierRecalculationService {

    private static final Logger log = LoggerFactory.getLogger(OrderTierRecalculationService.class);

    private final UserRepository userRepository;
    private final UserTierRepository userTierRepository;

    public OrderTierRecalculationService(UserRepository userRepository,
                                          UserTierRepository userTierRepository) {
        this.userRepository = userRepository;
        this.userTierRepository = userTierRepository;
    }

    /**
     * 사용자 등급을 최신 totalSpent 기준으로 재계산한다.
     *
     * <p>PESSIMISTIC_WRITE로 User를 잠그므로, 동일 사용자에 대한
     * 동시 등급 재계산이 직렬화된다. 두 주문이 거의 동시에 완료되어
     * 두 번 호출되더라도 마지막 호출이 최신 totalSpent를 반영한다.</p>
     *
     * @param userId 등급 재계산 대상 사용자 ID
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recalculateTier(Long userId) {
        User user = userRepository.findByIdWithLockAndTier(userId)
                .orElseThrow(() -> new ResourceNotFoundException("사용자", userId));

        userTierRepository.findFirstByMinSpentLessThanEqualOrderByTierLevelDesc(user.getTotalSpent())
                .ifPresent(newTier -> {
                    if (!newTier.getTierLevel().equals(user.getTier().getTierLevel())) {
                        log.info("등급 변경 - userId={}, {} → {}, totalSpent={}",
                                userId, user.getTier().getTierName(),
                                newTier.getTierName(), user.getTotalSpent());
                    }
                    user.updateTier(newTier);
                });
    }
}
