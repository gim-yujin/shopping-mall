package com.shop.domain.user.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * User 엔티티 단위 테스트 — 포인트, 누적 소비, 등급, 로그인 이력
 */
class UserEntityUnitTest {

    private User createUser() {
        return new User("testuser", "test@test.com", "hash", "테스터", "010-1234-5678");
    }

    // ==================== addTotalSpent ====================

    @Test
    @DisplayName("addTotalSpent — 양수 금액 누적")
    void addTotalSpent_positive() {
        User user = createUser();
        user.addTotalSpent(BigDecimal.valueOf(50000));

        assertThat(user.getTotalSpent()).isEqualByComparingTo("50000");

        user.addTotalSpent(BigDecimal.valueOf(30000));
        assertThat(user.getTotalSpent()).isEqualByComparingTo("80000");
    }

    @Test
    @DisplayName("addTotalSpent — 음수 금액(취소 등)으로 차감, 0 이하로 내려가지 않음")
    void addTotalSpent_negativeFlooredAtZero() {
        User user = createUser();
        user.addTotalSpent(BigDecimal.valueOf(10000));
        user.addTotalSpent(BigDecimal.valueOf(-50000)); // 0 미만 방어

        assertThat(user.getTotalSpent()).isEqualByComparingTo("0");
    }

    // ==================== addPoints ====================

    @Test
    @DisplayName("addPoints — 양수 포인트 적립")
    void addPoints_positive() {
        User user = createUser();
        user.addPoints(1000);

        assertThat(user.getPointBalance()).isEqualTo(1000);
    }

    @Test
    @DisplayName("addPoints — 음수 포인트, 0 이하로 내려가지 않음")
    void addPoints_negativeFlooredAtZero() {
        User user = createUser();
        user.addPoints(500);
        user.addPoints(-1000); // 0 미만 방어

        assertThat(user.getPointBalance()).isEqualTo(0);
    }

    // ==================== usePoints ====================

    @Test
    @DisplayName("usePoints — 정상 차감")
    void usePoints_success() {
        User user = createUser();
        user.addPoints(5000);

        user.usePoints(3000);

        assertThat(user.getPointBalance()).isEqualTo(2000);
    }

    @Test
    @DisplayName("usePoints — 잔액 부족 시 IllegalArgumentException")
    void usePoints_insufficientBalance_throwsException() {
        User user = createUser();
        user.addPoints(1000);

        assertThatThrownBy(() -> user.usePoints(2000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("포인트가 부족");
    }

    @Test
    @DisplayName("usePoints — 전액 사용 가능")
    void usePoints_exactBalance() {
        User user = createUser();
        user.addPoints(3000);

        user.usePoints(3000);
        assertThat(user.getPointBalance()).isEqualTo(0);
    }

    // ==================== updateTier ====================

    @Test
    @DisplayName("updateTier — 등급 변경")
    void updateTier_changesTier() {
        User user = createUser();
        UserTier newTier = mock(UserTier.class);

        user.updateTier(newTier);

        assertThat(user.getTier()).isSameAs(newTier);
    }

    // ==================== updateLastLogin ====================

    @Test
    @DisplayName("updateLastLogin — lastLoginAt 갱신")
    void updateLastLogin_updatesTimestamp() {
        User user = createUser();
        assertThat(user.getLastLoginAt()).isNull();

        user.updateLastLogin();

        assertThat(user.getLastLoginAt()).isNotNull();
        assertThat(user.getUpdatedAt()).isNotNull();
    }

    // ==================== 생성자 초기값 ====================

    @Test
    @DisplayName("생성자 — 초기값 검증: ROLE_USER, totalSpent=0, pointBalance=0, isActive=true")
    void constructor_setsDefaults() {
        User user = createUser();

        assertThat(user.getRole()).isEqualTo("ROLE_USER");
        assertThat(user.getTotalSpent()).isEqualByComparingTo("0");
        assertThat(user.getPointBalance()).isEqualTo(0);
        assertThat(user.getIsActive()).isTrue();
        assertThat(user.getCreatedAt()).isNotNull();
    }
}
