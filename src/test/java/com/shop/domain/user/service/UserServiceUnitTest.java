package com.shop.domain.user.service;

import com.shop.domain.user.dto.SignupRequest;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.repository.UserRepository;
import com.shop.domain.user.repository.UserTierRepository;
import com.shop.global.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceUnitTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserTierRepository userTierRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private CacheManager cacheManager;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, userTierRepository, passwordEncoder, cacheManager);
    }

    @Test
    @DisplayName("updateProfile 방어 검증 - 빈 이름")
    void updateProfile_blankName_throwsException() {
        assertThatThrownBy(() -> userService.updateProfile(1L, " ", "010-1234-5678", "valid@test.com"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이름을 입력해주세요");
    }

    @Test
    @DisplayName("updateProfile 방어 검증 - 잘못된 이메일")
    void updateProfile_invalidEmail_throwsException() {
        assertThatThrownBy(() -> userService.updateProfile(1L, "홍길동", "010-1234-5678", "invalid-email"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("올바른 이메일 형식");
    }


    @Test
    @DisplayName("signup 방어 검증 - 약한 비밀번호")
    void signup_weakPassword_throwsException() {
        assertThatThrownBy(() -> userService.signup(new SignupRequest(
                "weakuser",
                "weak@test.com",
                "password",
                "약한유저",
                "010-1234-5678"
        )))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("비밀번호는 영문, 숫자, 특수문자를 포함해야 합니다.");
    }

    @Test
    @DisplayName("changePassword 방어 검증 - 짧은 비밀번호")
    void changePassword_shortPassword_throwsException() {
        assertThatThrownBy(() -> userService.changePassword(1L, "Current123!", "Ab1!"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("비밀번호는 영문, 숫자, 특수문자를 포함해 8자 이상");
    }

    @Test
    @DisplayName("changePassword 성공 시 userDetails 캐시 evict")
    void changePassword_evictsUserDetailsCache() {
        User user = new User("tester", "tester@test.com", "encoded-current", "테스터", "010-1234-5678");
        Cache cache = org.mockito.Mockito.mock(Cache.class);

        when(userRepository.findByIdWithTier(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Current123!", "encoded-current")).thenReturn(true);
        when(passwordEncoder.matches("New1234!@", "encoded-current")).thenReturn(false);
        when(passwordEncoder.encode("New1234!@")).thenReturn("encoded-new");
        when(cacheManager.getCache("userDetails")).thenReturn(cache);

        userService.changePassword(1L, "Current123!", "New1234!@");

        verify(cache).evict("tester");
    }

    @Test
    @DisplayName("changePassword 방어 검증 - 기존과 동일한 새 비밀번호")
    void changePassword_samePassword_throwsException() {
        User user = new User("tester", "tester@test.com", "encoded-current", "테스터", "010-1234-5678");
        when(userRepository.findByIdWithTier(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Current123!", "encoded-current")).thenReturn(true);

        assertThatThrownBy(() -> userService.changePassword(1L, "Current123!", "Current123!"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("기존 비밀번호와 달라야 합니다");
    }
}
