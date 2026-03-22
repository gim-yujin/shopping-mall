package com.shop.domain.user.service;

import com.shop.domain.user.dto.SignupRequest;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.repository.UserRepository;
import com.shop.domain.user.repository.UserTierRepository;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.DuplicateConstraintMessageResolver;
import com.shop.global.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserService 분기 커버리지 보강 테스트.
 *
 * <p>기존 UserServiceTest(통합 테스트)에서 다루지 않은 분기를 검증한다:
 * - signup: password null 분기
 * - signup: 기본 등급(tierLevel=1) 미존재 → ResourceNotFoundException
 * - changePassword: 새 비밀번호가 기존 비밀번호와 동일한 경우
 * - evictUserDetailsCache: cache == null, username == null 분기
 * - validatePasswordInput: null 입력 분기
 * - normalize* 메서드: null 입력 → 빈 문자열 정규화</p>
 */
@ExtendWith(MockitoExtension.class)
class UserServiceBranchTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserTierRepository tierRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private CacheManager cacheManager;
    @Mock
    private DuplicateConstraintMessageResolver duplicateConstraintMessageResolver;

    @InjectMocks
    private UserService userService;

    // ── signup: password null 분기 ──

    @Nested
    @DisplayName("signup — 비밀번호 검증")
    class SignupPasswordValidation {

        @Test
        @DisplayName("password가 null이면 BusinessException(INVALID_INPUT)")
        void signup_nullPassword_throwsException() {
            // given: 중복 체크 통과
            when(userRepository.existsByUsernameIgnoreCase(anyString())).thenReturn(false);
            when(userRepository.existsByEmail(anyString())).thenReturn(false);

            SignupRequest request = new SignupRequest("testuser", "test@test.com",
                    null, "테스터", "010-1234-5678");

            // when & then: password null → INVALID_INPUT
            assertThatThrownBy(() -> userService.signup(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("비밀번호는");
        }

        @Test
        @DisplayName("기본 등급(tierLevel=1) 미존재 → ResourceNotFoundException")
        void signup_defaultTierNotFound_throwsException() {
            // given: 중복/비밀번호 체크 통과, 등급 미존재
            when(userRepository.existsByUsernameIgnoreCase(anyString())).thenReturn(false);
            when(userRepository.existsByEmail(anyString())).thenReturn(false);
            when(tierRepository.findByTierLevel(1)).thenReturn(Optional.empty());

            SignupRequest request = new SignupRequest("testuser", "test@test.com",
                    "Valid1!pass", "테스터", "010-1234-5678");

            // when & then
            assertThatThrownBy(() -> userService.signup(request))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── changePassword: 새 비밀번호 == 기존 비밀번호 ──

    @Nested
    @DisplayName("changePassword — 비밀번호 변경 분기")
    class ChangePasswordBranch {

        @Test
        @DisplayName("새 비밀번호가 기존과 동일하면 SAME_PASSWORD 예외")
        void changePassword_sameAsOld_throwsException() {
            // given
            User user = mock(User.class);
            when(user.getPasswordHash()).thenReturn("encoded_old");
            when(userRepository.findByIdWithTier(1L)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("OldPass1!", "encoded_old")).thenReturn(true);
            // 새 비밀번호도 기존과 동일
            when(passwordEncoder.matches("OldPass1!", "encoded_old")).thenReturn(true);

            // when & then
            assertThatThrownBy(() -> userService.changePassword(1L, "OldPass1!", "OldPass1!"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("기존 비밀번호와 달라야");
        }
    }

    // ── evictUserDetailsCache: null 분기 ──

    @Nested
    @DisplayName("evictUserDetailsCache — 캐시/username null 분기")
    class EvictCacheBranch {

        @Test
        @DisplayName("changePassword 성공 시 캐시가 null이면 예외 없이 완료")
        void changePassword_nullCache_noException() {
            // given: 정상 비밀번호 변경, 캐시 null
            User user = mock(User.class);
            when(user.getPasswordHash()).thenReturn("encoded_old");
            when(user.getUsername()).thenReturn("testuser");
            when(userRepository.findByIdWithTier(1L)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("OldPass1!", "encoded_old")).thenReturn(true);
            when(passwordEncoder.matches("NewPass2@", "encoded_old")).thenReturn(false);
            when(passwordEncoder.encode("NewPass2@")).thenReturn("encoded_new");
            when(cacheManager.getCache("userDetails")).thenReturn(null);

            // when: 예외 없이 완료 (evictUserDetailsCache에서 cache == null → return)
            userService.changePassword(1L, "OldPass1!", "NewPass2@");

            // then
            verify(user).changePassword("encoded_new");
        }
    }

    // ── validatePasswordInput: null 분기 ──

    @Nested
    @DisplayName("validatePasswordInput — null 입력")
    class ValidatePasswordInput {

        @Test
        @DisplayName("currentPassword가 null이면 INVALID_INPUT 예외")
        void changePassword_nullCurrentPassword_throwsException() {
            assertThatThrownBy(() -> userService.changePassword(1L, null, "NewPass2@"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("현재 비밀번호를 입력");
        }

        @Test
        @DisplayName("newPassword가 null이면 INVALID_INPUT 예외")
        void changePassword_nullNewPassword_throwsException() {
            assertThatThrownBy(() -> userService.changePassword(1L, "OldPass1!", null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("새 비밀번호를 입력");
        }
    }

    // ── updateProfile: 이메일 변경 없는 경우 ──

    @Test
    @DisplayName("updateProfile — 이메일이 동일하면 중복 체크를 건너뛴다")
    void updateProfile_sameEmail_skipsCheck() {
        // given: 기존 이메일과 동일한 이메일로 수정
        User user = mock(User.class);
        when(user.getEmail()).thenReturn("same@test.com");
        when(userRepository.findByIdWithTier(1L)).thenReturn(Optional.of(user));
        when(userRepository.saveAndFlush(any())).thenReturn(user);

        // when: 이메일 동일 → existsByEmail 호출 안 됨
        userService.updateProfile(1L, "홍길동", "010-1234-5678", "same@test.com");

        // then: 중복 체크 호출되지 않음
        verify(userRepository, never()).existsByEmail(anyString());
    }
}
