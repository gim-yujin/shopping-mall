package com.shop.domain.user.scheduler;

import com.shop.domain.order.repository.OrderRepository;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.entity.UserTier;
import com.shop.domain.user.repository.UserRepository;
import com.shop.domain.user.repository.UserTierHistoryRepository;
import com.shop.domain.user.repository.UserTierRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TierSchedulerUnitTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserTierRepository userTierRepository;
    @Mock
    private UserTierHistoryRepository tierHistoryRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private EntityManager entityManager;
    @Mock
    private PlatformTransactionManager txManager;

    private TierScheduler tierScheduler;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // TransactionTemplate이 콜백을 정상 실행하도록 모의 트랜잭션 상태 반환
        TransactionStatus mockStatus = mock(TransactionStatus.class);
        when(txManager.getTransaction(any())).thenReturn(mockStatus);

        tierScheduler = new TierScheduler(
                userRepository, userTierRepository, tierHistoryRepository,
                orderRepository, entityManager, txManager, 1);
    }

    @Test
    @DisplayName("등급 재산정은 페이지 단위로 순회하고 청크마다 flush/clear를 수행한다")
    void recalculateTiers_processesUsersByChunk() {
        UserTier tier = mock(UserTier.class);
        when(tier.getTierId()).thenReturn(1);

        User firstUser = mock(User.class);
        when(firstUser.getUserId()).thenReturn(1L);
        when(firstUser.getTier()).thenReturn(tier);

        User secondUser = mock(User.class);
        when(secondUser.getUserId()).thenReturn(2L);
        when(secondUser.getTier()).thenReturn(tier);

        when(orderRepository.findYearlySpentByUser(any(), any())).thenReturn(Collections.emptyList());
        when(userTierRepository.findByTierLevel(1)).thenReturn(Optional.of(tier));
        when(userTierRepository.findFirstByMinSpentLessThanEqualOrderByTierLevelDesc(any())).thenReturn(Optional.of(tier));

        // [BUG FIX] keyset pagination으로 변경됨 — chunkSize=1이므로
        // 첫 호출: lastUserId=0 → firstUser 반환 (1건, chunkSize와 같으므로 다음 청크 존재)
        // 두 번째: lastUserId=1 → secondUser 반환 (1건, chunkSize와 같으므로 다음 청크 존재)
        // 세 번째: lastUserId=2 → 빈 리스트 반환 → 루프 종료
        when(userRepository.findUsersAfterIdWithTier(eq(0L), any()))
                .thenReturn(List.of(firstUser));
        when(userRepository.findUsersAfterIdWithTier(eq(1L), any()))
                .thenReturn(List.of(secondUser));
        when(userRepository.findUsersAfterIdWithTier(eq(2L), any()))
                .thenReturn(Collections.emptyList());

        tierScheduler.recalculateTiers();

        verify(userRepository).findUsersAfterIdWithTier(eq(0L), any());
        verify(userRepository).findUsersAfterIdWithTier(eq(1L), any());
        verify(entityManager, times(2)).flush();
        verify(entityManager, times(2)).clear();
        verify(tierHistoryRepository, never()).save(any());
    }
}
