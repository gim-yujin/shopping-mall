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

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
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

    private TierScheduler tierScheduler;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        tierScheduler = new TierScheduler(
                userRepository, userTierRepository, tierHistoryRepository,
                orderRepository, entityManager, 1);
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

        when(userRepository.findAll(PageRequest.of(0, 1)))
                .thenReturn(new PageImpl<>(List.of(firstUser), PageRequest.of(0, 1), 2));
        when(userRepository.findAll(PageRequest.of(1, 1)))
                .thenReturn(new PageImpl<>(List.of(secondUser), PageRequest.of(1, 1), 2));

        tierScheduler.recalculateTiers();

        verify(userRepository).findAll(PageRequest.of(0, 1));
        verify(userRepository).findAll(PageRequest.of(1, 1));
        verify(entityManager, times(2)).flush();
        verify(entityManager, times(2)).clear();
        verify(tierHistoryRepository, never()).save(any());
    }
}
