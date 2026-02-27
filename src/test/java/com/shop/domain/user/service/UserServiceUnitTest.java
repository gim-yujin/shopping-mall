package com.shop.domain.user.service;

import com.shop.domain.user.dto.SignupRequest;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.entity.UserTier;
import com.shop.domain.user.repository.UserRepository;
import com.shop.domain.user.repository.UserTierRepository;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.DuplicateConstraintMessageResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    @Mock
    private DuplicateConstraintMessageResolver duplicateConstraintMessageResolver;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, userTierRepository, passwordEncoder, cacheManager, duplicateConstraintMessageResolver);
    }


    @Test
    @DisplayName("signup 시 trim/lower-case 정규화 값을 중복 검사와 저장에 사용")
    void signup_normalizesFields_forDuplicateCheckAndSave() {
        when(userRepository.existsByUsernameIgnoreCase("tester")).thenReturn(false);
        when(userRepository.existsByEmail("test@a.com")).thenReturn(false);
        UserTier defaultTier = mock(UserTier.class);
        when(userTierRepository.findByTierLevel(1)).thenReturn(Optional.of(defaultTier));
        when(passwordEncoder.encode("Pass1234!")).thenReturn("encoded");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User saved = userService.signup(new SignupRequest(
                " tester ",
                "Test@A.com ",
                "Pass1234!",
                " 테스터 ",
                " 010-1234-5678 "
        ));

        verify(userRepository).existsByUsernameIgnoreCase("tester");
        verify(userRepository).existsByEmail("test@a.com");
        assertThat(saved.getUsername()).isEqualTo("tester");
        assertThat(saved.getEmail()).isEqualTo("test@a.com");
        assertThat(saved.getName()).isEqualTo("테스터");
        assertThat(saved.getPhone()).isEqualTo("010-1234-5678");
    }

    @Test
    @DisplayName("signup 저장 중 UNIQUE 충돌을 DUPLICATE BusinessException으로 변환")
    void signup_duplicateOnSave_translatesToBusinessException() {
        when(userRepository.existsByUsernameIgnoreCase("tester")).thenReturn(false);
        when(userRepository.existsByEmail("test@a.com")).thenReturn(false);
        UserTier defaultTier = mock(UserTier.class);
        when(userTierRepository.findByTierLevel(1)).thenReturn(Optional.of(defaultTier));
        when(passwordEncoder.encode("Pass1234!")).thenReturn("encoded");
        DataIntegrityViolationException exception = new DataIntegrityViolationException("duplicate username");
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(exception);
        when(duplicateConstraintMessageResolver.resolve(exception)).thenReturn("이미 사용 중인 아이디입니다.");

        assertThatThrownBy(() -> userService.signup(new SignupRequest(
                "tester",
                "test@a.com",
                "Pass1234!",
                "테스터",
                "010-1234-5678"
        )))
                .isInstanceOf(BusinessException.class)
                .hasMessage("이미 사용 중인 아이디입니다.")
                .extracting("code")
                .isEqualTo("DUPLICATE");
    }

    @Test
    @DisplayName("signup 중복 아이디 검사는 대소문자를 무시한다")
    void signup_duplicateUsername_ignoresCase() {
        when(userRepository.existsByUsernameIgnoreCase("user")).thenReturn(true);

        assertThatThrownBy(() -> userService.signup(new SignupRequest(
                "User",
                "test@a.com",
                "Pass1234!",
                "테스터",
                "010-1234-5678"
        )))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 사용 중인 아이디");
    }

    @Test
    @DisplayName("updateProfile 이메일 비교는 정규화 값 기준으로 공백 우회를 막는다")
    void updateProfile_usesNormalizedEmail_forDuplicateCheckComparison() {
        User user = new User("tester", "test@a.com", "hash", "테스터", "010-1234-5678");
        when(userRepository.findByIdWithTier(1L)).thenReturn(Optional.of(user));

        userService.updateProfile(1L, " 새이름 ", " 010-1111-2222 ", "test@a.com ");

        verify(userRepository, never()).existsByEmail(any());
        assertThat(user.getEmail()).isEqualTo("test@a.com");
        assertThat(user.getName()).isEqualTo("새이름");
        assertThat(user.getPhone()).isEqualTo("010-1111-2222");
    }

    @Test
    @DisplayName("updateProfile 이메일 중복 체크도 소문자 정규화 값으로 수행")
    void updateProfile_duplicateEmailCheck_withNormalizedEmail() {
        User user = new User("tester", "origin@a.com", "hash", "테스터", "010-1234-5678");
        when(userRepository.findByIdWithTier(1L)).thenReturn(Optional.of(user));
        when(userRepository.existsByEmail("test@a.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.updateProfile(1L, "테스터", "010-1234-5678", " Test@A.com "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 사용 중인 이메일");

        verify(userRepository).existsByEmail("test@a.com");
    }

    @Test
    @DisplayName("updateProfile 저장 중 UNIQUE 충돌을 DUPLICATE BusinessException으로 변환")
    void updateProfile_duplicateOnSave_translatesToBusinessException() {
        User user = new User("tester", "origin@a.com", "hash", "테스터", "010-1234-5678");
        when(userRepository.findByIdWithTier(1L)).thenReturn(Optional.of(user));
        when(userRepository.existsByEmail("new@a.com")).thenReturn(false);
        DataIntegrityViolationException exception = new DataIntegrityViolationException("duplicate email");
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(exception);
        when(duplicateConstraintMessageResolver.resolve(exception)).thenReturn("이미 사용 중인 이메일입니다.");

        assertThatThrownBy(() -> userService.updateProfile(1L, "테스터", "010-1234-5678", "new@a.com"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("이미 사용 중인 이메일입니다.")
                .extracting("code")
                .isEqualTo("DUPLICATE");
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
