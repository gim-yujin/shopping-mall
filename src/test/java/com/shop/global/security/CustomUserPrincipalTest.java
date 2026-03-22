package com.shop.global.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CustomUserPrincipal 단위 테스트.
 *
 * <p>UserDetails 구현체의 모든 getter 메서드와 계정 상태 플래그를 검증한다.
 * 기존 테스트에서는 CustomUserPrincipal을 다른 테스트의 fixture로만 사용했으므로
 * getName(), getRole(), isAccountNonExpired() 등의 메서드가 커버되지 않아
 * LINE 67% / METHOD 45%였다.</p>
 */
class CustomUserPrincipalTest {

    @Test
    @DisplayName("모든 getter가 생성자에서 전달된 값을 정확히 반환한다")
    void allGetters_returnConstructorValues() {
        // given: 관리자 권한을 가진 사용자 Principal 생성
        CustomUserPrincipal principal = new CustomUserPrincipal(
                1L, "admin", "encodedPwd", "관리자", "ROLE_ADMIN",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        // then: 모든 필드가 정확히 반환되어야 한다
        assertThat(principal.getUserId()).isEqualTo(1L);
        assertThat(principal.getUsername()).isEqualTo("admin");
        assertThat(principal.getPassword()).isEqualTo("encodedPwd");
        assertThat(principal.getName()).isEqualTo("관리자");
        assertThat(principal.getRole()).isEqualTo("ROLE_ADMIN");
        assertThat(principal.getAuthorities()).hasSize(1);
    }

    @Test
    @DisplayName("UserDetails 계정 상태 플래그는 모두 true를 반환한다")
    void accountStatusFlags_allReturnTrue() {
        // given: 일반 사용자 Principal
        // CustomUserPrincipal은 계정 잠금/만료 기능을 사용하지 않으므로
        // 모든 상태 플래그가 true를 반환해야 한다
        CustomUserPrincipal principal = new CustomUserPrincipal(
                2L, "user", "pwd", "사용자", "ROLE_USER",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));

        // then: 모든 계정 상태 플래그 = true
        assertThat(principal.isAccountNonExpired()).isTrue();
        assertThat(principal.isAccountNonLocked()).isTrue();
        assertThat(principal.isCredentialsNonExpired()).isTrue();
        assertThat(principal.isEnabled()).isTrue();
    }
}
