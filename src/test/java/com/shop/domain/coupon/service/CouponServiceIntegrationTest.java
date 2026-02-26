package com.shop.domain.coupon.service;

import com.shop.domain.coupon.entity.Coupon;
import com.shop.domain.coupon.entity.UserCoupon;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * CouponService 통합 테스트 — 쿠폰 발급 비즈니스 로직 검증
 *
 * 검증 항목:
 * 1) issueCoupon (코드) 정상 발급
 * 2) issueCouponById (ID) 정상 발급
 * 3) 이미 발급된 쿠폰 중복 발급 방지
 * 4) 만료된 쿠폰 발급 방지
 * 5) 수량 소진된 쿠폰 발급 방지
 * 6) 존재하지 않는 쿠폰 코드
 * 7) getAvailableCoupons / getUserCoupons 조회
 *
 * 주의: 실제 PostgreSQL DB에 연결하여 테스트합니다.
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "logging.level.org.hibernate.SQL=WARN"
})
class CouponServiceIntegrationTest {

    @Autowired
    private CouponService couponService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long testUserId;

    // 테스트용 쿠폰 ID 추적 (정리용)
    private final List<Integer> testCouponIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // 활성 사용자 (쿠폰이 적거나 없는 사용자)
        testUserId = jdbcTemplate.queryForObject(
                """
                SELECT u.user_id FROM users u
                WHERE u.is_active = true AND u.role = 'ROLE_USER'
                ORDER BY (SELECT COUNT(*) FROM user_coupons uc WHERE uc.user_id = u.user_id) ASC
                LIMIT 1
                """,
                Long.class);

        System.out.println("  [setUp] 사용자 ID: " + testUserId);
    }

    @AfterEach
    void tearDown() {
        // 테스트 중 발급된 user_coupons 삭제
        for (Integer couponId : testCouponIds) {
            jdbcTemplate.update(
                    "DELETE FROM user_coupons WHERE user_id = ? AND coupon_id = ?",
                    testUserId, couponId);
        }

        // 테스트용 쿠폰 삭제 (테스트에서 생성한 것들)
        for (Integer couponId : testCouponIds) {
            // used_quantity 복원은 쿠폰 삭제로 불필요
            jdbcTemplate.update(
                    "DELETE FROM user_coupons WHERE coupon_id = ?", couponId);
            jdbcTemplate.update(
                    "DELETE FROM coupons WHERE coupon_id = ? AND coupon_code LIKE 'TEST_%'",
                    couponId);
        }
        testCouponIds.clear();
    }

    // ==================== 테스트 쿠폰 생성 헬퍼 ====================

    private Integer createTestCoupon(String code, String name, String discountType,
                                     int discountValue, Integer totalQuantity,
                                     boolean active, boolean expired) {
        String validFrom = "2024-01-01 00:00:00";
        String validUntil = expired ? "2024-12-31 23:59:59" : "2027-12-31 23:59:59";

        jdbcTemplate.update(
                """
                INSERT INTO coupons (coupon_code, coupon_name, discount_type, discount_value,
                    min_order_amount, max_discount, total_quantity, used_quantity,
                    valid_from, valid_until, is_active, created_at)
                VALUES (?, ?, ?, ?, 0, NULL, ?, 0, ?::timestamp, ?::timestamp, ?, NOW())
                """,
                code, name, discountType, discountValue,
                totalQuantity, validFrom, validUntil, active);

        Integer couponId = jdbcTemplate.queryForObject(
                "SELECT coupon_id FROM coupons WHERE coupon_code = ?",
                Integer.class, code);
        testCouponIds.add(couponId);
        return couponId;
    }

    // ==================== 정상 발급 ====================

    @Test
    @DisplayName("issueCoupon (코드) — 정상 발급")
    void issueCoupon_byCode_success() {
        // Given: 테스트 쿠폰 생성
        String couponCode = "TEST_CODE_" + System.currentTimeMillis();
        Integer couponId = createTestCoupon(couponCode, "테스트 쿠폰", "FIXED", 5000, 100, true, false);

        // When
        couponService.issueCoupon(testUserId, couponCode);

        // Then: user_coupons에 레코드 존재
        int count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_coupons WHERE user_id = ? AND coupon_id = ?",
                Integer.class, testUserId, couponId);
        assertThat(count).isEqualTo(1);

        // used_quantity 증가 확인
        int usedQty = jdbcTemplate.queryForObject(
                "SELECT used_quantity FROM coupons WHERE coupon_id = ?",
                Integer.class, couponId);
        assertThat(usedQty).isEqualTo(1);

        System.out.println("  [PASS] 쿠폰 코드 발급 성공: " + couponCode);
    }

    @Test
    @DisplayName("issueCouponById (ID) — 정상 발급")
    void issueCoupon_byId_success() {
        // Given
        String couponCode = "TEST_ID_" + System.currentTimeMillis();
        Integer couponId = createTestCoupon(couponCode, "테스트 ID 쿠폰", "PERCENT", 10, null, true, false);

        // When
        couponService.issueCouponById(testUserId, couponId);

        // Then
        int count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_coupons WHERE user_id = ? AND coupon_id = ?",
                Integer.class, testUserId, couponId);
        assertThat(count).isEqualTo(1);

        // is_used = false 확인
        Boolean isUsed = jdbcTemplate.queryForObject(
                "SELECT is_used FROM user_coupons WHERE user_id = ? AND coupon_id = ?",
                Boolean.class, testUserId, couponId);
        assertThat(isUsed).isFalse();

        System.out.println("  [PASS] 쿠폰 ID 발급 성공: couponId=" + couponId);
    }

    // ==================== 발급 예외 ====================

    @Test
    @DisplayName("issueCoupon 실패 — 이미 발급받은 쿠폰 중복 발급")
    void issueCoupon_alreadyIssued_throwsException() {
        // Given: 발급
        String couponCode = "TEST_DUP_" + System.currentTimeMillis();
        createTestCoupon(couponCode, "중복 테스트", "FIXED", 3000, 100, true, false);
        couponService.issueCoupon(testUserId, couponCode);

        // When & Then: 다시 발급 시도
        assertThatThrownBy(() -> couponService.issueCoupon(testUserId, couponCode))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 발급");

        System.out.println("  [PASS] 중복 발급 → BusinessException");
    }

    @Test
    @DisplayName("issueCoupon 실패 — 만료된 쿠폰")
    void issueCoupon_expired_throwsException() {
        // Given: 만료된 쿠폰
        String couponCode = "TEST_EXP_" + System.currentTimeMillis();
        createTestCoupon(couponCode, "만료 쿠폰", "FIXED", 1000, 100, true, true);

        // When & Then
        assertThatThrownBy(() -> couponService.issueCoupon(testUserId, couponCode))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("유효하지 않은");

        System.out.println("  [PASS] 만료 쿠폰 → BusinessException");
    }

    @Test
    @DisplayName("issueCoupon 실패 — 비활성 쿠폰")
    void issueCoupon_inactive_throwsException() {
        // Given: 비활성 쿠폰
        String couponCode = "TEST_INACT_" + System.currentTimeMillis();
        createTestCoupon(couponCode, "비활성 쿠폰", "FIXED", 1000, 100, false, false);

        // When & Then
        assertThatThrownBy(() -> couponService.issueCoupon(testUserId, couponCode))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("유효하지 않은");

        System.out.println("  [PASS] 비활성 쿠폰 → BusinessException");
    }

    @Test
    @DisplayName("issueCoupon 실패 — 수량 소진")
    void issueCoupon_quantityExhausted_throwsException() {
        // Given: 총 수량 1, used_quantity를 1로 설정 (소진)
        String couponCode = "TEST_QTY_" + System.currentTimeMillis();
        Integer couponId = createTestCoupon(couponCode, "소진 쿠폰", "FIXED", 1000, 1, true, false);
        jdbcTemplate.update("UPDATE coupons SET used_quantity = 1 WHERE coupon_id = ?", couponId);

        // When & Then
        assertThatThrownBy(() -> couponService.issueCoupon(testUserId, couponCode))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("수량이 모두 소진");

        System.out.println("  [PASS] 수량 소진 쿠폰 → BusinessException");
    }

    @Test
    @DisplayName("issueCoupon 실패 — 존재하지 않는 쿠폰 코드")
    void issueCoupon_nonExistentCode_throwsException() {
        assertThatThrownBy(() -> couponService.issueCoupon(testUserId, "NON_EXISTENT_CODE_XYZ"))
                .isInstanceOf(ResourceNotFoundException.class);

        System.out.println("  [PASS] 존재하지 않는 코드 → ResourceNotFoundException");
    }

    @Test
    @DisplayName("issueCouponById 실패 — 존재하지 않는 쿠폰 ID")
    void issueCouponById_nonExistentId_throwsException() {
        assertThatThrownBy(() -> couponService.issueCouponById(testUserId, 999999))
                .isInstanceOf(ResourceNotFoundException.class);

        System.out.println("  [PASS] 존재하지 않는 ID → ResourceNotFoundException");
    }

    // ==================== 조회 ====================

    @Test
    @DisplayName("getUserIssuedCouponIds — 발급받은 쿠폰 ID 목록")
    void getUserIssuedCouponIds_returnsIssuedIds() {
        // Given: 2개 쿠폰 발급
        String code1 = "TEST_LIST1_" + System.currentTimeMillis();
        String code2 = "TEST_LIST2_" + System.currentTimeMillis();
        Integer id1 = createTestCoupon(code1, "목록1", "FIXED", 1000, 100, true, false);
        Integer id2 = createTestCoupon(code2, "목록2", "FIXED", 2000, 100, true, false);

        couponService.issueCoupon(testUserId, code1);
        couponService.issueCoupon(testUserId, code2);

        // When
        Set<Integer> issuedIds = couponService.getUserIssuedCouponIds(testUserId);

        // Then
        assertThat(issuedIds).contains(id1, id2);

        System.out.println("  [PASS] 발급 쿠폰 ID 목록 조회: " + issuedIds.size() + "개");
    }

    @Test
    @DisplayName("getAvailableCoupons — 주문 시점 isAvailable 규칙과 동일한 쿠폰만 반환")
    void getAvailableCoupons_returnsOnlyOrderAvailableCoupons() {
        // Given: 발급 후 상태를 변경해 조회 필터링 대상 쿠폰들을 준비
        String availableCode = "TEST_AVAIL_OK_" + System.currentTimeMillis();
        String inactiveCode = "TEST_AVAIL_INACTIVE_" + System.currentTimeMillis();
        String soldOutCode = "TEST_AVAIL_SOLDOUT_" + System.currentTimeMillis();
        String notStartedCode = "TEST_AVAIL_NOT_STARTED_" + System.currentTimeMillis();

        Integer availableCouponId = createTestCoupon(availableCode, "조회 가능", "FIXED", 1000, 100, true, false);
        Integer inactiveCouponId = createTestCoupon(inactiveCode, "비활성", "FIXED", 1000, 100, true, false);
        Integer soldOutCouponId = createTestCoupon(soldOutCode, "소진", "FIXED", 1000, 2, true, false);
        Integer notStartedCouponId = createTestCoupon(notStartedCode, "시작 전", "FIXED", 1000, 100, true, false);

        couponService.issueCoupon(testUserId, availableCode);
        couponService.issueCoupon(testUserId, inactiveCode);
        couponService.issueCoupon(testUserId, soldOutCode);
        couponService.issueCoupon(testUserId, notStartedCode);

        jdbcTemplate.update("UPDATE coupons SET is_active = false WHERE coupon_id = ?", inactiveCouponId);
        jdbcTemplate.update("UPDATE coupons SET used_quantity = total_quantity WHERE coupon_id = ?", soldOutCouponId);
        jdbcTemplate.update("UPDATE coupons SET valid_from = NOW() + interval '1 day' WHERE coupon_id = ?", notStartedCouponId);

        // When
        List<UserCoupon> available = couponService.getAvailableCoupons(testUserId);

        // Then: 주문 시점의 UserCoupon.isAvailable() 규칙과 동일해야 함
        assertThat(available)
                .extracting(uc -> uc.getCoupon().getCouponId())
                .contains(availableCouponId)
                .doesNotContain(inactiveCouponId, soldOutCouponId, notStartedCouponId);

        assertThat(available)
                .as("조회된 모든 쿠폰은 주문 시점 사용 가능 규칙(isAvailable)을 만족해야 함")
                .allMatch(UserCoupon::isAvailable);

        System.out.println("  [PASS] 사용 가능 쿠폰 조회/주문 규칙 일치 검증: " + available.size() + "개");
    }



    @Test
    @DisplayName("getActiveCoupons — 활성 + 유효 기간 내 쿠폰만 반환")
    void getActiveCoupons_returnsOnlyActiveAndValidCoupons() {
        // Given
        String activeCode = "TEST_ACTIVE_" + System.currentTimeMillis();
        String inactiveCode = "TEST_INACTIVE_" + System.currentTimeMillis();
        String expiredCode = "TEST_EXPIRED_" + System.currentTimeMillis();

        Integer activeId = createTestCoupon(activeCode, "활성 쿠폰", "FIXED", 1000, 100, true, false);
        Integer inactiveId = createTestCoupon(inactiveCode, "비활성 쿠폰", "FIXED", 1000, 100, false, false);
        Integer expiredId = createTestCoupon(expiredCode, "만료 쿠폰", "FIXED", 1000, 100, true, true);

        // When
        Page<Coupon> page = couponService.getActiveCoupons(PageRequest.of(0, 100));

        // Then
        Set<Integer> ids = new HashSet<>();
        page.getContent().forEach(coupon -> ids.add(coupon.getCouponId()));

        assertThat(ids)
                .as("활성 + 유효한 쿠폰은 조회되어야 함")
                .contains(activeId);
        assertThat(ids)
                .as("비활성/만료 쿠폰은 활성 쿠폰 조회에서 제외되어야 함")
                .doesNotContain(inactiveId, expiredId);

        System.out.println("  [PASS] 활성 쿠폰 조회 필터 검증 완료");
    }

    @Test
    @DisplayName("getUserCoupons — 사용자 발급 쿠폰 페이지 조회")
    void getUserCoupons_returnsIssuedCouponsForUser() {
        // Given
        String code = "TEST_USER_PAGE_" + System.currentTimeMillis();
        Integer couponId = createTestCoupon(code, "사용자 페이지 쿠폰", "FIXED", 1500, 100, true, false);
        couponService.issueCoupon(testUserId, code);

        // When
        Page<UserCoupon> page = couponService.getUserCoupons(testUserId, PageRequest.of(0, 20));

        // Then
        assertThat(page.getContent())
                .as("사용자 쿠폰 페이지에 방금 발급한 쿠폰이 포함되어야 함")
                .anyMatch(uc -> uc.getCoupon().getCouponId().equals(couponId));

        System.out.println("  [PASS] 사용자 쿠폰 페이지 조회 성공: total=" + page.getTotalElements());
    }

    // ==================== usedQuantity 정합성 ====================

    @Test
    @DisplayName("issueCoupon 연속 발급 — usedQuantity 정확히 증가")
    void issueCoupon_incrementsUsedQuantity() {
        // Given: 테스트 쿠폰 (수량 충분)
        String code = "TEST_INCR_" + System.currentTimeMillis();
        Integer couponId = createTestCoupon(code, "수량 테스트", "FIXED", 500, 100, true, false);

        int usedBefore = jdbcTemplate.queryForObject(
                "SELECT used_quantity FROM coupons WHERE coupon_id = ?",
                Integer.class, couponId);

        // When: 발급
        couponService.issueCoupon(testUserId, code);

        // Then
        int usedAfter = jdbcTemplate.queryForObject(
                "SELECT used_quantity FROM coupons WHERE coupon_id = ?",
                Integer.class, couponId);
        assertThat(usedAfter).isEqualTo(usedBefore + 1);

        System.out.println("  [PASS] usedQuantity: " + usedBefore + " → " + usedAfter);
    }
}
