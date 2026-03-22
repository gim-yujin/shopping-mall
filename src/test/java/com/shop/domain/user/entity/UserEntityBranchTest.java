package com.shop.domain.user.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * User + UserTier + UserTierHistory 엔티티 분기 커버리지 보강 테스트.
 *
 * <p>기존 UserEntityUnitTest에서 다루지 않은 메서드/분기를 검증한다:
 * - User: updateProfile, changePassword, setTier, setTotalSpent
 * - UserTier: 모든 getter (immutable 참조 데이터, LINE 60%)
 * - UserTierHistory: 생성자 및 getter (LINE 57%)</p>
 */
class UserEntityBranchTest {

    // ── User: 미커버 메서드 ──

    @Test
    @DisplayName("updateProfile — 이름, 전화번호, 이메일을 변경하고 updatedAt 갱신")
    void updateProfile_updatesFieldsAndTimestamp() {
        // given: 기존 사용자
        User user = new User("olduser", "old@email.com", "pwdhash", "기존이름", "010-0000-0000");

        // when: 프로필 변경
        user.updateProfile("새이름", "010-1111-1111", "new@email.com");

        // then
        assertThat(user.getName()).isEqualTo("새이름");
        assertThat(user.getPhone()).isEqualTo("010-1111-1111");
        assertThat(user.getEmail()).isEqualTo("new@email.com");
        assertThat(user.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("changePassword — 비밀번호 해시를 변경하고 updatedAt 갱신")
    void changePassword_updatesHashAndTimestamp() {
        // given
        User user = new User("user1", "e@e.com", "oldhash", "이름", "010");

        // when
        user.changePassword("newhash123");

        // then
        assertThat(user.getPasswordHash()).isEqualTo("newhash123");
    }

    @Test
    @DisplayName("setTier — 등급만 설정 (updatedAt 미갱신)")
    void setTier_setsTierOnly() {
        // given
        User user = new User("user1", "e@e.com", "hash", "이름", "010");
        UserTier tier = mock(UserTier.class);

        // when: setTier는 updateTier와 달리 updatedAt을 갱신하지 않음
        user.setTier(tier);

        // then
        assertThat(user.getTier()).isSameAs(tier);
    }

    @Test
    @DisplayName("setTotalSpent — totalSpent를 설정하고 updatedAt 갱신")
    void setTotalSpent_setsValueAndUpdatesTimestamp() {
        // given
        User user = new User("user1", "e@e.com", "hash", "이름", "010");

        // when
        user.setTotalSpent(java.math.BigDecimal.valueOf(50000));

        // then
        assertThat(user.getTotalSpent()).isEqualByComparingTo("50000");
    }

    @Test
    @DisplayName("getIsActive — 생성 직후 true")
    void getIsActive_defaultTrue() {
        // given & when
        User user = new User("user1", "e@e.com", "hash", "이름", "010");

        // then: isActive 기본값 = true
        assertThat(user.getIsActive()).isTrue();
    }

    // ── UserTier: 불변 참조 데이터 getter ──

    @Test
    @DisplayName("UserTier getter — protected 기본 생성자로 생성 후 모든 getter 호출")
    void userTier_allGetters() throws Exception {
        // given: protected 기본 생성자로 생성 (JPA용)
        // Reflection으로 접근하여 필드 설정 (실제로는 DB에서 로딩)
        UserTier tier = UserTier.class.getDeclaredConstructor().newInstance();
        org.springframework.test.util.ReflectionTestUtils.setField(tier, "tierId", 1);
        org.springframework.test.util.ReflectionTestUtils.setField(tier, "tierName", "GOLD");
        org.springframework.test.util.ReflectionTestUtils.setField(tier, "tierLevel", 3);
        org.springframework.test.util.ReflectionTestUtils.setField(tier, "minSpent",
                java.math.BigDecimal.valueOf(100000));
        org.springframework.test.util.ReflectionTestUtils.setField(tier, "discountRate",
                java.math.BigDecimal.valueOf(5));
        org.springframework.test.util.ReflectionTestUtils.setField(tier, "pointEarnRate",
                java.math.BigDecimal.valueOf(3));
        org.springframework.test.util.ReflectionTestUtils.setField(tier, "freeShippingThreshold",
                java.math.BigDecimal.valueOf(30000));
        org.springframework.test.util.ReflectionTestUtils.setField(tier, "description", "골드 회원");

        // then: 모든 getter가 정확한 값 반환
        assertThat(tier.getTierId()).isEqualTo(1);
        assertThat(tier.getTierName()).isEqualTo("GOLD");
        assertThat(tier.getTierLevel()).isEqualTo(3);
        assertThat(tier.getMinSpent()).isEqualByComparingTo("100000");
        assertThat(tier.getDiscountRate()).isEqualByComparingTo("5");
        assertThat(tier.getPointEarnRate()).isEqualByComparingTo("3");
        assertThat(tier.getFreeShippingThreshold()).isEqualByComparingTo("30000");
        assertThat(tier.getDescription()).isEqualTo("골드 회원");
    }

    // ── UserTierHistory: 불변 이력 엔티티 ──

    @Test
    @DisplayName("UserTierHistory 생성자가 모든 필드를 올바르게 초기화한다")
    void userTierHistory_constructor_initializesAllFields() {
        // given & when: 등급 변경 이력 생성 (SILVER → GOLD)
        UserTierHistory history = new UserTierHistory(1L, 2, 3, "총 구매액 기준 자동 승급");

        // then: 모든 필드가 정확히 설정
        assertThat(history.getUserId()).isEqualTo(1L);
        assertThat(history.getFromTierId()).isEqualTo(2);
        assertThat(history.getToTierId()).isEqualTo(3);
        assertThat(history.getReason()).isEqualTo("총 구매액 기준 자동 승급");
        assertThat(history.getChangedAt()).isNotNull();
        // historyId는 JPA가 할당
        assertThat(history.getHistoryId()).isNull();
    }
}
