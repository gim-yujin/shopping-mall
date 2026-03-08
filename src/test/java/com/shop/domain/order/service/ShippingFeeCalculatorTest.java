package com.shop.domain.order.service;

import com.shop.domain.user.entity.UserTier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Field;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ShippingFeeCalculator 단위 테스트.
 *
 * 순수 계산 로직만 검증하므로 Spring 컨텍스트, DB 연결 없이 실행된다.
 * UserTier는 JPA 엔티티라 protected 생성자만 가지므로,
 * 리플렉션으로 freeShippingThreshold를 주입하여 테스트 픽스처를 구성한다.
 *
 * 검증 대상:
 * 1) calculateShippingFee — 등급별 무료배송 기준에 따른 배송비 계산
 *    - null threshold (BASIC 등급, 무료배송 혜택 없음) → 기본 배송비
 *    - 0 threshold (최상위 등급, 무조건 무료배송) → 0원
 *    - 주문 금액이 기준 미만 / 이상 / 정확히 일치하는 경우
 * 2) calculateFinalAmount — 최종 결제 금액 계산
 *    - 정상 계산, 음수 클램핑, 차감이 0인 경우
 */
class ShippingFeeCalculatorTest {

    private ShippingFeeCalculator calculator;

    /** 기본 배송비 상수 (ShippingFeeCalculator.SHIPPING_FEE_BASE와 동일) */
    private static final BigDecimal BASE_FEE = new BigDecimal("3000");

    @BeforeEach
    void setUp() {
        calculator = new ShippingFeeCalculator();
    }

    // ========================================================================
    // calculateShippingFee 테스트
    // ========================================================================

    @Test
    @DisplayName("freeShippingThreshold=null(BASIC 등급) → 기본 배송비 3,000원")
    void shippingFee_nullThreshold_returnsBaseFee() {
        // Given: BASIC 등급 — DB 스키마상 free_shipping_threshold가 NULL
        // [BUG FIX 검증] 기존 코드는 null.compareTo()로 NPE가 발생했음
        UserTier basicTier = createTierWithThreshold(null);
        BigDecimal orderAmount = new BigDecimal("100000");

        // When
        BigDecimal fee = calculator.calculateShippingFee(basicTier, orderAmount);

        // Then: 무료배송 혜택 없음 → 항상 기본 배송비
        assertThat(fee).isEqualByComparingTo(BASE_FEE);
    }

    @Test
    @DisplayName("freeShippingThreshold=0(VIP 등급) → 무조건 무료배송")
    void shippingFee_zeroThreshold_alwaysFree() {
        // Given: 최상위 등급 — threshold=0 → 금액 무관하게 무료배송
        UserTier vipTier = createTierWithThreshold(BigDecimal.ZERO);
        BigDecimal orderAmount = new BigDecimal("1000"); // 소액이어도 무료

        // When
        BigDecimal fee = calculator.calculateShippingFee(vipTier, orderAmount);

        // Then
        assertThat(fee).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("주문 금액 >= threshold → 무료배송")
    void shippingFee_amountAboveThreshold_free() {
        // Given: threshold 30,000원, 주문 50,000원
        UserTier tier = createTierWithThreshold(new BigDecimal("30000"));
        BigDecimal orderAmount = new BigDecimal("50000");

        // When
        BigDecimal fee = calculator.calculateShippingFee(tier, orderAmount);

        // Then: 기준 초과 → 무료배송
        assertThat(fee).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("주문 금액 == threshold (정확히 일치) → 무료배송")
    void shippingFee_amountExactlyAtThreshold_free() {
        // Given: 경계값 — 주문 금액이 threshold와 정확히 일치
        BigDecimal threshold = new BigDecimal("30000");
        UserTier tier = createTierWithThreshold(threshold);

        // When
        BigDecimal fee = calculator.calculateShippingFee(tier, threshold);

        // Then: >= 조건이므로 정확히 일치해도 무료배송
        assertThat(fee).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("주문 금액 < threshold → 기본 배송비 부과")
    void shippingFee_amountBelowThreshold_baseFee() {
        // Given: threshold 30,000원, 주문 20,000원
        UserTier tier = createTierWithThreshold(new BigDecimal("30000"));
        BigDecimal orderAmount = new BigDecimal("20000");

        // When
        BigDecimal fee = calculator.calculateShippingFee(tier, orderAmount);

        // Then: 기준 미달 → 기본 배송비
        assertThat(fee).isEqualByComparingTo(BASE_FEE);
    }

    @Test
    @DisplayName("주문 금액이 threshold보다 1원 부족 → 기본 배송비 부과")
    void shippingFee_amountOneWonBelowThreshold_baseFee() {
        // Given: 경계값 — threshold 30,000원에서 1원 부족
        UserTier tier = createTierWithThreshold(new BigDecimal("30000"));
        BigDecimal orderAmount = new BigDecimal("29999");

        // When
        BigDecimal fee = calculator.calculateShippingFee(tier, orderAmount);

        // Then: 미달 → 기본 배송비
        assertThat(fee).isEqualByComparingTo(BASE_FEE);
    }

    // ========================================================================
    // calculateFinalAmount 테스트
    // ========================================================================

    @Test
    @DisplayName("정상 계산: 상품금액 - 차감 + 배송비")
    void finalAmount_normalCalculation() {
        // Given: 50,000원 상품 - 5,000원 차감 + 3,000원 배송비 = 48,000원
        BigDecimal itemTotal = new BigDecimal("50000");
        BigDecimal deduction = new BigDecimal("5000");
        BigDecimal shippingFee = new BigDecimal("3000");

        // When
        BigDecimal result = calculator.calculateFinalAmount(itemTotal, deduction, shippingFee);

        // Then
        assertThat(result).isEqualByComparingTo(new BigDecimal("48000"));
    }

    @Test
    @DisplayName("차감이 상품금액을 초과하면 최종 금액은 0으로 클램핑")
    void finalAmount_negativeClampedToZero() {
        // Given: 10,000원 상품에 20,000원 차감 → 음수가 되지만 0으로 클램핑
        // 실제로는 포인트 사용 상한 로직이 이 상황을 방지하지만,
        // 방어적 프로그래밍으로 계산기 자체에서도 음수를 차단한다.
        BigDecimal itemTotal = new BigDecimal("10000");
        BigDecimal deduction = new BigDecimal("20000");
        BigDecimal shippingFee = BigDecimal.ZERO;

        // When
        BigDecimal result = calculator.calculateFinalAmount(itemTotal, deduction, shippingFee);

        // Then
        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("차감 없이 상품금액 + 배송비만 적용")
    void finalAmount_noDeduction() {
        // Given: 할인/포인트 사용 없이 주문
        BigDecimal itemTotal = new BigDecimal("25000");
        BigDecimal deduction = BigDecimal.ZERO;
        BigDecimal shippingFee = new BigDecimal("3000");

        // When
        BigDecimal result = calculator.calculateFinalAmount(itemTotal, deduction, shippingFee);

        // Then: 25,000 + 3,000 = 28,000
        assertThat(result).isEqualByComparingTo(new BigDecimal("28000"));
    }

    @Test
    @DisplayName("무료배송 + 전액 할인 → 최종 0원")
    void finalAmount_freeShippingAndFullDiscount() {
        // Given: 100% 할인 쿠폰 + 무료배송 시나리오
        BigDecimal itemTotal = new BigDecimal("10000");
        BigDecimal deduction = new BigDecimal("10000");
        BigDecimal shippingFee = BigDecimal.ZERO;

        // When
        BigDecimal result = calculator.calculateFinalAmount(itemTotal, deduction, shippingFee);

        // Then
        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @ParameterizedTest(name = "상품={0}, 차감={1}, 배송비={2} → 최종={3}")
    @DisplayName("파라미터화 테스트: 다양한 금액 조합")
    @CsvSource({
            // 상품금액, 차감, 배송비, 기대 최종금액
            "100000, 10000, 0,     90000",   // VIP: 할인 + 무료배송
            "100000, 10000, 3000,  93000",   // SILVER: 할인 + 배송비
            "15000,  0,     3000,  18000",   // BASIC: 할인 없음 + 배송비
            "50000,  50000, 3000,  3000",    // 전액 할인 + 배송비만
            "1000,   5000,  0,     0",       // 차감 > 상품금액 → 0 클램핑
    })
    void finalAmount_parameterized(String itemStr, String deductStr, String feeStr, String expectedStr) {
        BigDecimal result = calculator.calculateFinalAmount(
                new BigDecimal(itemStr), new BigDecimal(deductStr), new BigDecimal(feeStr));

        assertThat(result).isEqualByComparingTo(new BigDecimal(expectedStr));
    }

    // ========================================================================
    // 테스트 헬퍼
    // ========================================================================

    /**
     * UserTier 엔티티의 freeShippingThreshold를 리플렉션으로 설정한다.
     *
     * UserTier는 JPA 엔티티라 protected 생성자와 getter만 가지며,
     * 필드를 외부에서 설정할 수 있는 public API가 없다.
     * 단위 테스트에서는 DB 없이 특정 등급 조건을 재현해야 하므로
     * 리플렉션으로 필드를 직접 주입한다.
     *
     * 대안: 테스트 전용 팩토리 또는 Builder를 UserTier에 추가할 수 있으나,
     * 프로덕션 코드에 테스트 전용 API를 노출하지 않는 정책을 유지한다.
     */
    private UserTier createTierWithThreshold(BigDecimal threshold) {
        try {
            // protected 생성자 접근
            var constructor = UserTier.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            UserTier tier = constructor.newInstance();

            // freeShippingThreshold 필드 설정
            Field field = UserTier.class.getDeclaredField("freeShippingThreshold");
            field.setAccessible(true);
            field.set(tier, threshold);

            return tier;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("UserTier 테스트 픽스처 생성 실패", e);
        }
    }
}
