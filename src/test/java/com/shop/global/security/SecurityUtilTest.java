package com.shop.global.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SecurityUtil 단위 테스트.
 *
 * <p>SecurityUtil은 SecurityContextHolder에서 현재 사용자 정보를 추출하는 유틸리티다.
 * getCurrentUserId()와 getCurrentUser() 모두 4개 분기를 가진다:
 * - auth == null (미인증)
 * - auth.getPrincipal()이 CustomUserPrincipal이 아닌 경우 (예: "anonymousUser")
 * - auth != null && principal instanceof CustomUserPrincipal (정상 인증)
 *
 * <p>기존 테스트에서 SecurityUtil 자체를 직접 테스트하지 않아
 * LINE 50% / BRANCH 38%로 커버리지 공백이 있었다.</p>
 */
class SecurityUtilTest {

    @AfterEach
    void tearDown() {
        // 테스트 간 격리: SecurityContext를 초기화하여 상태 누출 방지
        SecurityContextHolder.clearContext();
    }

    // ── getCurrentUserId ──

    @Test
    @DisplayName("인증된 사용자 → userId Optional 반환")
    void getCurrentUserId_authenticated_returnsUserId() {
        // given: CustomUserPrincipal이 설정된 인증 컨텍스트
        setAuthentication(42L);

        // when
        Optional<Long> userId = SecurityUtil.getCurrentUserId();

        // then: 인증된 사용자의 ID가 반환
        assertThat(userId).isPresent().contains(42L);
    }

    @Test
    @DisplayName("미인증 상태 → empty Optional 반환")
    void getCurrentUserId_noAuthentication_returnsEmpty() {
        // given: SecurityContext에 Authentication이 없는 상태
        // SecurityContextHolder.clearContext()가 @AfterEach에서 호출되므로
        // 이 테스트 시작 시 auth == null

        // when
        Optional<Long> userId = SecurityUtil.getCurrentUserId();

        // then: auth == null 분기 → Optional.empty()
        assertThat(userId).isEmpty();
    }

    @Test
    @DisplayName("Principal이 CustomUserPrincipal이 아닌 경우 → empty Optional 반환")
    void getCurrentUserId_nonCustomPrincipal_returnsEmpty() {
        // given: Principal이 String("anonymousUser")인 인증 — 익명 사용자
        // auth != null이지만 instanceof CustomUserPrincipal 분기가 false
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("anonymousUser", null));

        // when
        Optional<Long> userId = SecurityUtil.getCurrentUserId();

        // then: Principal 타입 불일치 → Optional.empty()
        assertThat(userId).isEmpty();
    }

    // ── getCurrentUser ──

    @Test
    @DisplayName("인증된 사용자 → CustomUserPrincipal Optional 반환")
    void getCurrentUser_authenticated_returnsPrincipal() {
        // given: CustomUserPrincipal이 설정된 인증 컨텍스트
        setAuthentication(7L);

        // when
        Optional<CustomUserPrincipal> user = SecurityUtil.getCurrentUser();

        // then: 인증된 사용자 Principal이 반환
        assertThat(user).isPresent();
        assertThat(user.get().getUserId()).isEqualTo(7L);
        assertThat(user.get().getUsername()).isEqualTo("testuser");
    }

    @Test
    @DisplayName("미인증 상태 → getCurrentUser도 empty Optional 반환")
    void getCurrentUser_noAuthentication_returnsEmpty() {
        // when: auth == null 분기
        Optional<CustomUserPrincipal> user = SecurityUtil.getCurrentUser();

        // then
        assertThat(user).isEmpty();
    }

    @Test
    @DisplayName("Principal이 String인 경우 → getCurrentUser도 empty 반환")
    void getCurrentUser_stringPrincipal_returnsEmpty() {
        // given: Principal이 String인 인증 (예: OAuth2 또는 RememberMe 토큰)
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user@email.com", null));

        // when
        Optional<CustomUserPrincipal> user = SecurityUtil.getCurrentUser();

        // then: instanceof 분기 false → Optional.empty()
        assertThat(user).isEmpty();
    }

    /** SecurityContextHolder에 CustomUserPrincipal 기반 인증을 설정한다. */
    private void setAuthentication(Long userId) {
        CustomUserPrincipal principal = new CustomUserPrincipal(
                userId, "testuser", "password", "테스트", "ROLE_USER",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
