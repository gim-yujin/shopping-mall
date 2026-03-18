package com.shop.domain.order.service;

import com.shop.domain.user.entity.User;
import com.shop.domain.user.entity.UserTier;
import com.shop.domain.user.repository.UserRepository;
import com.shop.domain.user.repository.UserTierRepository;
import com.shop.global.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * [Phase 6] OrderTierRecalculationService 단위 테스트.
 *
 * <p>비동기 후처리에서 호출되는 등급 재계산 로직을 검증한다:
 * <ul>
 *   <li>최신 totalSpent 기준으로 올바른 등급이 조회되는지</li>
 *   <li>등급이 변경되면 User 엔티티가 갱신되는지</li>
 *   <li>사용자가 존재하지 않으면 ResourceNotFoundException이 발생하는지</li>
 * </ul></p>
 */
@ExtendWith(MockitoExtension.class)
class OrderTierRecalculationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserTierRepository userTierRepository;

    @InjectMocks
    private OrderTierRecalculationService service;

    @Test
    @DisplayName("totalSpent에 맞는 등급으로 사용자를 갱신한다")
    void recalculatesTierBasedOnTotalSpent() {
        // given
        Long userId = 1L;
        User user = mock(User.class);
        UserTier currentTier = mock(UserTier.class);
        UserTier newTier = mock(UserTier.class);

        when(user.getTier()).thenReturn(currentTier);
        when(user.getTotalSpent()).thenReturn(new BigDecimal("500000"));
        when(currentTier.getTierLevel()).thenReturn(1);
        when(newTier.getTierLevel()).thenReturn(2);
        when(newTier.getTierName()).thenReturn("SILVER");
        when(currentTier.getTierName()).thenReturn("BRONZE");
        when(userRepository.findByIdWithLockAndTier(userId)).thenReturn(Optional.of(user));
        when(userTierRepository.findFirstByMinSpentLessThanEqualOrderByTierLevelDesc(
                new BigDecimal("500000"))).thenReturn(Optional.of(newTier));

        // when
        service.recalculateTier(userId);

        // then
        verify(user).updateTier(newTier);
    }

    @Test
    @DisplayName("등급 변경이 없으면 갱신은 호출되지만 레벨은 유지된다")
    void noTierChangeStillUpdates() {
        // given
        Long userId = 1L;
        User user = mock(User.class);
        UserTier sameTier = mock(UserTier.class);

        when(user.getTier()).thenReturn(sameTier);
        when(user.getTotalSpent()).thenReturn(new BigDecimal("10000"));
        when(sameTier.getTierLevel()).thenReturn(1);
        when(userRepository.findByIdWithLockAndTier(userId)).thenReturn(Optional.of(user));
        when(userTierRepository.findFirstByMinSpentLessThanEqualOrderByTierLevelDesc(
                new BigDecimal("10000"))).thenReturn(Optional.of(sameTier));

        // when
        service.recalculateTier(userId);

        // then: 같은 등급이라도 updateTier는 호출됨 (idempotent)
        verify(user).updateTier(sameTier);
    }

    @Test
    @DisplayName("사용자가 존재하지 않으면 ResourceNotFoundException이 발생한다")
    void throwsWhenUserNotFound() {
        // given
        when(userRepository.findByIdWithLockAndTier(999L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service.recalculateTier(999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("매칭되는 등급이 없으면 updateTier가 호출되지 않는다")
    void noMatchingTierDoesNotUpdate() {
        // given
        Long userId = 1L;
        User user = mock(User.class);
        when(user.getTotalSpent()).thenReturn(BigDecimal.ZERO);
        when(userRepository.findByIdWithLockAndTier(userId)).thenReturn(Optional.of(user));
        when(userTierRepository.findFirstByMinSpentLessThanEqualOrderByTierLevelDesc(any()))
                .thenReturn(Optional.empty());

        // when
        service.recalculateTier(userId);

        // then
        verify(user, never()).updateTier(any());
    }
}
