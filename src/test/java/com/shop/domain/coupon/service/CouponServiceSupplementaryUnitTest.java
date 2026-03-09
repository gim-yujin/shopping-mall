package com.shop.domain.coupon.service;

import com.shop.domain.coupon.dto.AdminCouponRequest;
import com.shop.domain.coupon.dto.CouponStats;
import com.shop.domain.coupon.entity.Coupon;
import com.shop.domain.coupon.entity.DiscountType;
import com.shop.domain.coupon.entity.UserCoupon;
import com.shop.domain.coupon.repository.CouponRepository;
import com.shop.domain.coupon.repository.UserCouponRepository;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * CouponService 보충 단위 테스트.
 *
 * <p>기존 CouponServiceUnitTest는 issueCouponById의 2개 경로만 커버한다.
 * 이 테스트는 나머지 모든 메서드와 분기를 커버한다:</p>
 * <ul>
 *   <li>조회 메서드: getActiveCoupons, getUserCoupons, getAvailableCoupons, getUserIssuedCouponIds</li>
 *   <li>issueCoupon (코드 기반): 성공, 존재하지 않는 코드</li>
 *   <li>issueToUser 실패 경로: 수량 소진(isQuantityExhausted), 이미 발급, 레이스 컨디션(incrementUsedQuantityIfAvailable=0), DB 유니크 제약 위반</li>
 *   <li>Admin: getCouponStats, getAllCouponsForAdmin, findByIdForAdmin(성공/실패)</li>
 *   <li>Admin CRUD: createCoupon(성공/중복코드/날짜오류), updateCoupon(성공/미존재/날짜오류), toggleCouponActive(성공/미존재)</li>
 *   <li>validateCouponDates: 종료일==시작일, 종료일&lt;시작일</li>
 * </ul>
 *
 * <p>커버리지 목표: 45% → 85%+ (Branch 57% → 80%+)</p>
 */
@ExtendWith(MockitoExtension.class)
class CouponServiceSupplementaryUnitTest {

    @Mock
    private CouponRepository couponRepository;
    @Mock
    private UserCouponRepository userCouponRepository;

    @InjectMocks
    private CouponService couponService;

    // ── 픽스처 ──────────────────────────────────────────────────

    private Coupon createRealCoupon() {
        return new Coupon(
                "TEST100", "테스트 쿠폰", DiscountType.FIXED,
                new BigDecimal("3000"), new BigDecimal("20000"), null,
                100,
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusDays(30)
        );
    }

    private AdminCouponRequest createAdminRequest() {
        AdminCouponRequest req = new AdminCouponRequest();
        req.setCouponCode("NEW2025");
        req.setCouponName("신규 쿠폰");
        req.setDiscountType(DiscountType.PERCENT);
        req.setDiscountValue(new BigDecimal("10"));
        req.setMinOrderAmount(new BigDecimal("30000"));
        req.setMaxDiscount(new BigDecimal("5000"));
        req.setTotalQuantity(50);
        req.setValidFrom(LocalDateTime.of(2025, 6, 1, 0, 0));
        req.setValidUntil(LocalDateTime.of(2025, 8, 31, 23, 59));
        return req;
    }

    // ═══════════════════════════════════════════════════════════
    // 조회 메서드
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("조회 메서드")
    class QueryTests {

        @Test
        @DisplayName("getActiveCoupons — 활성 쿠폰 목록을 반환한다")
        void getActiveCoupons() {
            // given
            Page<Coupon> page = new PageImpl<>(List.of(createRealCoupon()));
            when(couponRepository.findActiveCoupons(any())).thenReturn(page);

            // when
            Page<Coupon> result = couponService.getActiveCoupons(PageRequest.of(0, 20));

            // then
            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("getUserCoupons — 사용자 쿠폰 목록을 반환한다")
        void getUserCoupons() {
            // given
            Page<UserCoupon> page = new PageImpl<>(Collections.emptyList());
            when(userCouponRepository.findByUserId(eq(1L), any())).thenReturn(page);

            // when
            Page<UserCoupon> result = couponService.getUserCoupons(1L, PageRequest.of(0, 20));

            // then
            assertThat(result.getContent()).isEmpty();
        }

        @Test
        @DisplayName("getAvailableCoupons — 사용 가능 쿠폰 목록을 반환한다")
        void getAvailableCoupons() {
            // given
            when(userCouponRepository.findAvailableCoupons(1L)).thenReturn(Collections.emptyList());

            // when
            var result = couponService.getAvailableCoupons(1L);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("getUserIssuedCouponIds — 발급받은 쿠폰 ID 집합을 반환한다")
        void getUserIssuedCouponIds() {
            // given
            when(userCouponRepository.findCouponIdsByUserId(1L)).thenReturn(Set.of(10, 20));

            // when
            Set<Integer> result = couponService.getUserIssuedCouponIds(1L);

            // then
            assertThat(result).containsExactlyInAnyOrder(10, 20);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // issueCoupon (쿠폰 코드 기반 발급)
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("issueCoupon — 쿠폰 코드 기반 발급")
    class IssueCouponByCodeTests {

        @Test
        @DisplayName("유효한 코드로 발급 성공한다")
        void issueCoupon_success() {
            // given
            Coupon coupon = mock(Coupon.class);
            when(couponRepository.findByCouponCode("SAVE10")).thenReturn(Optional.of(coupon));
            when(coupon.isIssuable()).thenReturn(true);
            when(coupon.getCouponId()).thenReturn(5);
            when(coupon.getValidUntil()).thenReturn(LocalDateTime.now().plusDays(30));
            when(couponRepository.incrementUsedQuantityIfAvailable(5)).thenReturn(1);
            when(userCouponRepository.existsByUserIdAndCoupon_CouponId(1L, 5)).thenReturn(false);

            // when
            couponService.issueCoupon(1L, "SAVE10");

            // then
            verify(userCouponRepository).save(any(UserCoupon.class));
        }

        @Test
        @DisplayName("존재하지 않는 쿠폰 코드면 ResourceNotFoundException을 던진다")
        void issueCoupon_notFound() {
            // given
            when(couponRepository.findByCouponCode("INVALID")).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> couponService.issueCoupon(1L, "INVALID"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // issueToUser 실패 경로
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("issueToUser — 다양한 실패 경로")
    class IssueToUserFailureTests {

        @Test
        @DisplayName("수량 소진 시 COUPON_SOLD_OUT 예외를 던진다")
        void issueToUser_quantityExhausted() {
            // given: isIssuable=false + isQuantityExhausted=true → COUPON_SOLD_OUT 분기
            Coupon coupon = mock(Coupon.class);
            when(couponRepository.findById(5)).thenReturn(Optional.of(coupon));
            when(coupon.isIssuable()).thenReturn(false);
            when(coupon.isQuantityExhausted()).thenReturn(true);

            // when & then: 기존 테스트는 isQuantityExhausted=false만 커버
            assertThatThrownBy(() -> couponService.issueCouponById(1L, 5))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("소진");
        }

        @Test
        @DisplayName("이미 발급받은 사용자면 ALREADY_ISSUED 예외를 던진다")
        void issueToUser_alreadyIssued() {
            // given: isIssuable=true이지만 이미 발급 이력 존재
            Coupon coupon = mock(Coupon.class);
            when(couponRepository.findById(5)).thenReturn(Optional.of(coupon));
            when(coupon.isIssuable()).thenReturn(true);
            when(coupon.getCouponId()).thenReturn(5);
            when(userCouponRepository.existsByUserIdAndCoupon_CouponId(1L, 5)).thenReturn(true);

            // when & then
            assertThatThrownBy(() -> couponService.issueCouponById(1L, 5))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("이미 발급");
        }

        @Test
        @DisplayName("incrementUsedQuantityIfAvailable가 0을 반환하면 레이스 컨디션으로 COUPON_SOLD_OUT")
        void issueToUser_raceCondition_soldOut() {
            // given: 검증 통과했지만 실제 UPDATE 시점에 수량 소진
            Coupon coupon = mock(Coupon.class);
            when(couponRepository.findById(5)).thenReturn(Optional.of(coupon));
            when(coupon.isIssuable()).thenReturn(true);
            when(coupon.getCouponId()).thenReturn(5);
            when(userCouponRepository.existsByUserIdAndCoupon_CouponId(1L, 5)).thenReturn(false);
            // 핵심: 동시 요청으로 수량이 이미 소진되어 UPDATE 0건
            when(couponRepository.incrementUsedQuantityIfAvailable(5)).thenReturn(0);

            // when & then
            assertThatThrownBy(() -> couponService.issueCouponById(1L, 5))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("소진");

            verify(userCouponRepository, never()).save(any());
        }

        @Test
        @DisplayName("DB 유니크 제약 위반(동시 발급) 시 ALREADY_ISSUED 예외를 던진다")
        void issueToUser_dataIntegrityViolation() {
            // given: increment 성공했지만 save 시점에 UNIQUE 제약(uk_user_coupon_user_coupon) 위반
            Coupon coupon = mock(Coupon.class);
            when(couponRepository.findById(5)).thenReturn(Optional.of(coupon));
            when(coupon.isIssuable()).thenReturn(true);
            when(coupon.getCouponId()).thenReturn(5);
            when(coupon.getValidUntil()).thenReturn(LocalDateTime.now().plusDays(30));
            when(userCouponRepository.existsByUserIdAndCoupon_CouponId(1L, 5)).thenReturn(false);
            when(couponRepository.incrementUsedQuantityIfAvailable(5)).thenReturn(1);
            when(userCouponRepository.save(any(UserCoupon.class)))
                    .thenThrow(new DataIntegrityViolationException("unique constraint"));

            // when & then: DataIntegrityViolationException → BusinessException으로 변환
            assertThatThrownBy(() -> couponService.issueCouponById(1L, 5))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("이미 발급");
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Admin 조회
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Admin 조회")
    class AdminQueryTests {

        @Test
        @DisplayName("getCouponStats — 쿠폰 통계를 집계하여 반환한다")
        void getCouponStats() {
            // given
            when(couponRepository.count()).thenReturn(50L);
            when(couponRepository.countActiveCoupons()).thenReturn(30L);
            when(userCouponRepository.count()).thenReturn(200L);
            when(userCouponRepository.countUsedCoupons()).thenReturn(80L);

            // when
            CouponStats stats = couponService.getCouponStats();

            // then
            assertThat(stats.totalCoupons()).isEqualTo(50);
            assertThat(stats.activeCoupons()).isEqualTo(30);
            assertThat(stats.totalIssued()).isEqualTo(200);
            assertThat(stats.totalUsed()).isEqualTo(80);
        }

        @Test
        @DisplayName("getAllCouponsForAdmin — 전체 쿠폰 목록을 반환한다")
        void getAllCouponsForAdmin() {
            // given
            Page<Coupon> page = new PageImpl<>(List.of(createRealCoupon()));
            when(couponRepository.findAllByOrderByCreatedAtDesc(any())).thenReturn(page);

            // when
            Page<Coupon> result = couponService.getAllCouponsForAdmin(PageRequest.of(0, 20));

            // then
            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("findByIdForAdmin — 존재하는 쿠폰을 반환한다")
        void findByIdForAdmin_success() {
            // given
            Coupon coupon = createRealCoupon();
            when(couponRepository.findById(5)).thenReturn(Optional.of(coupon));

            // when
            Coupon result = couponService.findByIdForAdmin(5);

            // then
            assertThat(result.getCouponCode()).isEqualTo("TEST100");
        }

        @Test
        @DisplayName("findByIdForAdmin — 존재하지 않으면 ResourceNotFoundException을 던진다")
        void findByIdForAdmin_notFound() {
            // given
            when(couponRepository.findById(999)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> couponService.findByIdForAdmin(999))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Admin CRUD
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("createCoupon")
    class CreateCouponTests {

        @Test
        @DisplayName("정상 생성 — 쿠폰을 저장하고 반환한다")
        void createCoupon_success() {
            // given
            AdminCouponRequest req = createAdminRequest();
            when(couponRepository.existsByCouponCode("NEW2025")).thenReturn(false);
            when(couponRepository.save(any(Coupon.class))).thenAnswer(inv -> inv.getArgument(0));

            // when
            Coupon result = couponService.createCoupon(req);

            // then
            assertThat(result.getCouponName()).isEqualTo("신규 쿠폰");
            verify(couponRepository).save(any(Coupon.class));
        }

        @Test
        @DisplayName("중복 쿠폰 코드 — DUPLICATE_COUPON_CODE 예외를 던진다")
        void createCoupon_duplicateCode() {
            // given
            AdminCouponRequest req = createAdminRequest();
            when(couponRepository.existsByCouponCode("NEW2025")).thenReturn(true);

            // when & then
            assertThatThrownBy(() -> couponService.createCoupon(req))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("이미 존재하는 쿠폰 코드");

            verify(couponRepository, never()).save(any());
        }

        @Test
        @DisplayName("유효 종료일이 시작일보다 앞이면 INVALID_DATES 예외를 던진다")
        void createCoupon_invalidDates_before() {
            // given: validUntil < validFrom
            AdminCouponRequest req = createAdminRequest();
            req.setValidFrom(LocalDateTime.of(2025, 8, 31, 23, 59));
            req.setValidUntil(LocalDateTime.of(2025, 6, 1, 0, 0));

            // when & then
            assertThatThrownBy(() -> couponService.createCoupon(req))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("유효 종료일은 시작일 이후");
        }

        @Test
        @DisplayName("유효 종료일이 시작일과 동일하면 INVALID_DATES 예외를 던진다")
        void createCoupon_invalidDates_equal() {
            // given: validUntil == validFrom (같은 시각은 유효 기간이 0이므로 거부)
            LocalDateTime same = LocalDateTime.of(2025, 6, 1, 0, 0);
            AdminCouponRequest req = createAdminRequest();
            req.setValidFrom(same);
            req.setValidUntil(same);

            // when & then
            assertThatThrownBy(() -> couponService.createCoupon(req))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("유효 종료일은 시작일 이후");
        }
    }

    @Nested
    @DisplayName("updateCoupon")
    class UpdateCouponTests {

        @Test
        @DisplayName("정상 수정 — 기존 쿠폰을 업데이트하고 반환한다")
        void updateCoupon_success() {
            // given
            Coupon coupon = createRealCoupon();
            AdminCouponRequest req = createAdminRequest();
            when(couponRepository.findById(5)).thenReturn(Optional.of(coupon));

            // when
            Coupon result = couponService.updateCoupon(5, req);

            // then: update()가 호출되어 쿠폰명이 변경됨
            assertThat(result.getCouponName()).isEqualTo("신규 쿠폰");
        }

        @Test
        @DisplayName("존재하지 않는 쿠폰 ID — ResourceNotFoundException을 던진다")
        void updateCoupon_notFound() {
            // given
            AdminCouponRequest req = createAdminRequest();
            when(couponRepository.findById(999)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> couponService.updateCoupon(999, req))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("유효 날짜 오류 — INVALID_DATES 예외를 던진다")
        void updateCoupon_invalidDates() {
            // given: 날짜 검증은 DB 조회보다 먼저 실행됨
            AdminCouponRequest req = createAdminRequest();
            req.setValidFrom(LocalDateTime.of(2025, 12, 31, 23, 59));
            req.setValidUntil(LocalDateTime.of(2025, 1, 1, 0, 0));

            // when & then
            assertThatThrownBy(() -> couponService.updateCoupon(5, req))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("유효 종료일은 시작일 이후");
        }
    }

    @Nested
    @DisplayName("toggleCouponActive")
    class ToggleCouponActiveTests {

        @Test
        @DisplayName("정상 토글 — 쿠폰 활성 상태를 반전한다")
        void toggleCouponActive_success() {
            // given
            Coupon coupon = createRealCoupon();
            assertThat(coupon.getIsActive()).isTrue(); // 생성 시 기본값 true
            when(couponRepository.findById(5)).thenReturn(Optional.of(coupon));

            // when
            couponService.toggleCouponActive(5);

            // then: toggleActive() 호출 후 isActive가 false로 변경
            assertThat(coupon.getIsActive()).isFalse();
        }

        @Test
        @DisplayName("존재하지 않는 쿠폰 ID — ResourceNotFoundException을 던진다")
        void toggleCouponActive_notFound() {
            // given
            when(couponRepository.findById(999)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> couponService.toggleCouponActive(999))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
