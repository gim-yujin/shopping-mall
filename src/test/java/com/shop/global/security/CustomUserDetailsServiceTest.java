package com.shop.global.security;

import com.shop.domain.user.entity.User;
import com.shop.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * CustomUserDetailsService 단위 테스트.
 *
 * Spring Security의 인증 진입점인 loadUserByUsername() 메서드를 검증한다.
 * 정상 조회, 사용자 미존재, 비활성 계정, username 정규화(대소문자/공백) 분기를 커버한다.
 *
 * @Cacheable("userDetails")이 적용되어 있지만, 단위 테스트에서는 캐시가 동작하지 않으므로
 * 매 호출마다 리포지토리가 실행되는 것을 전제로 테스트한다.
 */
@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    private CustomUserDetailsService userDetailsService;

    @BeforeEach
    void setUp() {
        userDetailsService = new CustomUserDetailsService(userRepository);
    }

    /**
     * 활성 사용자 픽스처를 생성한다.
     */
    private User createActiveUser() {
        User user = new User("testuser", "test@example.com", "$2a$10$hashedpassword",
                "테스트 사용자", "010-1234-5678");
        ReflectionTestUtils.setField(user, "userId", 1L);
        // User 생성자에서 isActive=true, role="ROLE_USER"로 초기화됨
        return user;
    }

    @Test
    @DisplayName("정상 사용자 조회 → CustomUserPrincipal 반환")
    void loadUserByUsername_activeUser_returnsUserDetails() {
        // given
        User user = createActiveUser();
        when(userRepository.findByUsernameIgnoreCase("testuser")).thenReturn(Optional.of(user));

        // when
        UserDetails result = userDetailsService.loadUserByUsername("testuser");

        // then: CustomUserPrincipal이 올바른 값으로 생성됨
        assertThat(result).isInstanceOf(CustomUserPrincipal.class);
        CustomUserPrincipal principal = (CustomUserPrincipal) result;
        assertThat(principal.getUserId()).isEqualTo(1L);
        assertThat(principal.getUsername()).isEqualTo("testuser");
        assertThat(principal.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("존재하지 않는 사용자 → UsernameNotFoundException")
    void loadUserByUsername_notFound_throwsException() {
        // given: DB에 해당 username이 없음
        when(userRepository.findByUsernameIgnoreCase("unknown")).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("unknown"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("사용자를 찾을 수 없습니다");
    }

    @Test
    @DisplayName("비활성 계정 → UsernameNotFoundException")
    void loadUserByUsername_inactiveUser_throwsException() {
        // given: isActive=false인 사용자 (관리자에 의해 비활성화됨)
        User user = createActiveUser();
        ReflectionTestUtils.setField(user, "isActive", false);
        when(userRepository.findByUsernameIgnoreCase("testuser")).thenReturn(Optional.of(user));

        // when & then: 비활성 계정은 로그인 차단
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("testuser"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("비활성화된 계정");
    }

    @Test
    @DisplayName("username 정규화 — 대문자+공백 입력 시 trim+lowercase 후 조회")
    void loadUserByUsername_normalizesTrimAndLowercase() {
        // given: "  TestUser  " → "testuser"로 정규화
        // @Cacheable 키도 동일하게 정규화되므로 대소문자/공백 차이로 캐시 미스가 발생하지 않는다
        User user = createActiveUser();
        when(userRepository.findByUsernameIgnoreCase("testuser")).thenReturn(Optional.of(user));

        // when
        UserDetails result = userDetailsService.loadUserByUsername("  TestUser  ");

        // then: 정규화된 "testuser"로 조회됨
        verify(userRepository).findByUsernameIgnoreCase("testuser");
        assertThat(result).isNotNull();
    }
}
