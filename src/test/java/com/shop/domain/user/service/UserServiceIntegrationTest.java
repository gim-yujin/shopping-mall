package com.shop.domain.user.service;

import com.shop.domain.user.dto.SignupRequest;
import com.shop.domain.user.entity.User;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
import com.shop.global.security.CustomUserDetailsService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * UserService 통합 테스트 — 회원가입, 프로필 수정, 비밀번호 변경
 *
 * 검증 항목:
 * 1) signup 정상: 사용자 생성, 기본 등급 부여, 비밀번호 암호화
 * 2) signup 예외: 중복 username, 중복 email
 * 3) findById / findByUsername: 존재/미존재
 * 4) updateProfile 정상: 이름/전화/이메일 변경
 * 5) updateProfile 예외: 이미 사용 중인 이메일
 * 6) changePassword 정상: 비밀번호 변경 후 로그인 가능
 * 7) changePassword 예외: 현재 비밀번호 불일치
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "logging.level.org.hibernate.SQL=WARN"
})
class UserServiceIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CustomUserDetailsService customUserDetailsService;

    @Autowired
    private CacheManager cacheManager;

    private final List<Long> createdUserIds = new ArrayList<>();

    // 기존 사용자 (프로필/비밀번호 테스트용)
    private Long existingUserId;
    private String existingOriginalEmail;
    private String existingOriginalName;
    private String existingOriginalPhone;
    private String existingOriginalPasswordHash;

    @BeforeEach
    void setUp() {
        // 기존 활성 사용자 1명 (프로필/비밀번호 테스트용)
        existingUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM users WHERE is_active = true AND role = 'ROLE_USER' ORDER BY user_id LIMIT 1",
                Long.class);

        var state = jdbcTemplate.queryForMap(
                "SELECT email, name, phone, password_hash FROM users WHERE user_id = ?",
                existingUserId);
        existingOriginalEmail = (String) state.get("email");
        existingOriginalName = (String) state.get("name");
        existingOriginalPhone = (String) state.get("phone");
        existingOriginalPasswordHash = (String) state.get("password_hash");

        clearUserDetailsCache();
        System.out.println("  [setUp] 기존 사용자 ID: " + existingUserId);
    }

    @AfterEach
    void tearDown() {
        // 테스트에서 생성한 사용자 삭제
        for (Long userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM users WHERE user_id = ?", userId);
        }
        createdUserIds.clear();

        // 기존 사용자 원본 복원
        jdbcTemplate.update(
                "UPDATE users SET email = ?, name = ?, phone = ?, password_hash = ? WHERE user_id = ?",
                existingOriginalEmail, existingOriginalName, existingOriginalPhone,
                existingOriginalPasswordHash, existingUserId);
    }

    private void clearUserDetailsCache() {
        var cache = cacheManager.getCache("userDetails");
        if (cache != null) {
            cache.clear();
        }
    }

    private String uniqueSuffix() {
        return String.valueOf(System.currentTimeMillis());
    }

    // ==================== signup ====================

    @Test
    @DisplayName("signup 성공 — 사용자 생성, 기본 등급, 비밀번호 암호화")
    void signup_success() {
        // Given
        String suffix = uniqueSuffix();
        SignupRequest request = new SignupRequest(
                "testuser_" + suffix,
                "test_" + suffix + "@example.com",
                "password123!",
                "테스트유저",
                "010-1234-5678");

        // When
        User user = userService.signup(request);
        createdUserIds.add(user.getUserId());

        // Then
        assertThat(user.getUserId()).isNotNull();
        assertThat(user.getUsername()).isEqualTo("testuser_" + suffix);
        assertThat(user.getEmail()).isEqualTo("test_" + suffix + "@example.com");
        assertThat(user.getRole()).isEqualTo("ROLE_USER");

        // 기본 등급 (tier_level = 1) 부여 확인
        assertThat(user.getTier()).isNotNull();
        assertThat(user.getTier().getTierLevel()).isEqualTo(1);

        // 초기값 확인
        assertThat(user.getTotalSpent()).isEqualByComparingTo("0");
        assertThat(user.getPointBalance()).isEqualTo(0);
        assertThat(user.getIsActive()).isTrue();

        // 비밀번호 암호화 확인 (평문이 아님)
        String storedHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE user_id = ?",
                String.class, user.getUserId());
        assertThat(storedHash).isNotEqualTo("password123!");
        assertThat(passwordEncoder.matches("password123!", storedHash)).isTrue();

        System.out.println("  [PASS] 회원가입 성공: " + user.getUsername());
    }

    @Test
    @DisplayName("signup 실패 — 중복 username")
    void signup_duplicateUsername_throwsException() {
        // Given: 먼저 가입
        String suffix = uniqueSuffix();
        SignupRequest first = new SignupRequest(
                "dupuser_" + suffix, "first_" + suffix + "@test.com",
                "password123!", "첫번째", null);
        User user = userService.signup(first);
        createdUserIds.add(user.getUserId());

        // When & Then: 같은 username으로 다시 가입
        SignupRequest duplicate = new SignupRequest(
                "dupuser_" + suffix, "second_" + suffix + "@test.com",
                "password123!", "두번째", null);

        assertThatThrownBy(() -> userService.signup(duplicate))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 사용 중인 아이디");

        System.out.println("  [PASS] 중복 username → BusinessException");
    }

    @Test
    @DisplayName("signup 실패 — 중복 email")
    void signup_duplicateEmail_throwsException() {
        // Given
        String suffix = uniqueSuffix();
        SignupRequest first = new SignupRequest(
                "emailuser1_" + suffix, "dupemail_" + suffix + "@test.com",
                "password123!", "첫번째", null);
        User user = userService.signup(first);
        createdUserIds.add(user.getUserId());

        // When & Then
        SignupRequest duplicate = new SignupRequest(
                "emailuser2_" + suffix, "dupemail_" + suffix + "@test.com",
                "password123!", "두번째", null);

        assertThatThrownBy(() -> userService.signup(duplicate))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 사용 중인 이메일");

        System.out.println("  [PASS] 중복 email → BusinessException");
    }

    // ==================== findById / findByUsername ====================

    @Test
    @DisplayName("findById 성공")
    void findById_success() {
        User user = userService.findById(existingUserId);
        assertThat(user).isNotNull();
        assertThat(user.getUserId()).isEqualTo(existingUserId);

        System.out.println("  [PASS] findById: " + user.getUsername());
    }

    @Test
    @DisplayName("findById 실패 — 존재하지 않는 사용자")
    void findById_notFound_throwsException() {
        Long maxId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(user_id), 0) FROM users", Long.class);

        assertThatThrownBy(() -> userService.findById(maxId + 9999))
                .isInstanceOf(ResourceNotFoundException.class);

        System.out.println("  [PASS] 존재하지 않는 사용자 → ResourceNotFoundException");
    }

    @Test
    @DisplayName("findByUsername 성공")
    void findByUsername_success() {
        String username = jdbcTemplate.queryForObject(
                "SELECT username FROM users WHERE user_id = ?",
                String.class, existingUserId);

        User user = userService.findByUsername(username);
        assertThat(user.getUserId()).isEqualTo(existingUserId);

        System.out.println("  [PASS] findByUsername: " + username);
    }

    @Test
    @DisplayName("findByUsername 실패 — 존재하지 않는 username")
    void findByUsername_notFound_throwsException() {
        assertThatThrownBy(() -> userService.findByUsername("nonexistent_user_xyz_99999"))
                .isInstanceOf(ResourceNotFoundException.class);

        System.out.println("  [PASS] 존재하지 않는 username → ResourceNotFoundException");
    }

    // ==================== updateProfile ====================

    @Test
    @DisplayName("updateProfile 성공 — 이름, 전화번호, 이메일 변경")
    void updateProfile_success() {
        String suffix = uniqueSuffix();
        String newEmail = "updated_" + suffix + "@test.com";

        // When
        userService.updateProfile(existingUserId, "수정된이름", "010-9999-8888", newEmail);

        // Then
        var updated = jdbcTemplate.queryForMap(
                "SELECT name, phone, email FROM users WHERE user_id = ?", existingUserId);
        assertThat(updated.get("name")).isEqualTo("수정된이름");
        assertThat(updated.get("phone")).isEqualTo("010-9999-8888");
        assertThat(updated.get("email")).isEqualTo(newEmail);

        System.out.println("  [PASS] 프로필 수정 완료");
    }

    @Test
    @DisplayName("updateProfile — 이메일 변경 없이 다른 정보만 변경")
    void updateProfile_sameEmail_success() {
        // When: 기존 이메일 그대로, 이름만 변경
        userService.updateProfile(existingUserId, "이름만변경", "010-0000-0000", existingOriginalEmail);

        // Then
        String updatedName = jdbcTemplate.queryForObject(
                "SELECT name FROM users WHERE user_id = ?",
                String.class, existingUserId);
        assertThat(updatedName).isEqualTo("이름만변경");

        System.out.println("  [PASS] 이메일 변경 없이 이름만 수정");
    }

    @Test
    @DisplayName("updateProfile 실패 — 다른 사용자의 이메일로 변경")
    void updateProfile_duplicateEmail_throwsException() {
        // Given: 다른 사용자의 이메일 조회
        String otherEmail = jdbcTemplate.queryForObject(
                "SELECT email FROM users WHERE user_id != ? AND is_active = true LIMIT 1",
                String.class, existingUserId);

        // When & Then
        assertThatThrownBy(() ->
                userService.updateProfile(existingUserId, "테스트", "010-0000-0000", otherEmail))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 사용 중인 이메일");

        System.out.println("  [PASS] 중복 이메일로 프로필 수정 → BusinessException");
    }

    // ==================== changePassword ====================

    @Test
    @DisplayName("changePassword 성공 — 새 비밀번호로 변경")
    void changePassword_success() {
        // Given: 알려진 비밀번호로 설정
        String knownPassword = "known_password_123!";
        String newHash = passwordEncoder.encode(knownPassword);
        jdbcTemplate.update("UPDATE users SET password_hash = ? WHERE user_id = ?",
                newHash, existingUserId);

        // When
        userService.changePassword(existingUserId, knownPassword, "new_password_456!");

        // Then: 새 비밀번호로 검증
        String updatedHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE user_id = ?",
                String.class, existingUserId);
        assertThat(passwordEncoder.matches("new_password_456!", updatedHash)).isTrue();
        assertThat(passwordEncoder.matches(knownPassword, updatedHash)).isFalse();

        System.out.println("  [PASS] 비밀번호 변경 성공");
    }

    @Test
    @DisplayName("changePassword 후 userDetails 캐시가 즉시 무효화되어 새 비밀번호만 유효")
    void changePassword_evictsUserDetailsCache_immediately() {
        String username = jdbcTemplate.queryForObject(
                "SELECT username FROM users WHERE user_id = ?",
                String.class, existingUserId);

        String knownPassword = "known_password_123!";
        jdbcTemplate.update("UPDATE users SET password_hash = ? WHERE user_id = ?",
                passwordEncoder.encode(knownPassword), existingUserId);

        UserDetails before = customUserDetailsService.loadUserByUsername(username);

        userService.changePassword(existingUserId, knownPassword, "new_password_456!");

        UserDetails after = customUserDetailsService.loadUserByUsername(username);
        assertThat(passwordEncoder.matches("new_password_456!", after.getPassword())).isTrue();
        assertThat(passwordEncoder.matches(knownPassword, after.getPassword())).isFalse();
        assertThat(after.getPassword()).isNotEqualTo(before.getPassword());
    }

    @Test
    @DisplayName("changePassword 실패 — 현재 비밀번호 불일치")
    void changePassword_wrongCurrentPassword_throwsException() {
        assertThatThrownBy(() ->
                userService.changePassword(existingUserId, "wrong_password_999", "new_password_456!"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("현재 비밀번호가 일치하지 않습니다");

        System.out.println("  [PASS] 잘못된 현재 비밀번호 → BusinessException");
    }
}
