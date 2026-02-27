package com.shop.domain.coupon.entity;

/**
 * [P2-8] 쿠폰 할인 유형 열거형.
 *
 * 기존 문제: discount_type이 String("FIXED", "PERCENT")으로 관리되어
 * "PERCENT".equals(discountType) 같은 문자열 비교가 산재해 있었다.
 * 오타·잘못된 값이 컴파일 타임에 잡히지 않고, IDE 자동완성도 불가능했다.
 *
 * 수정: @Enumerated(EnumType.STRING)으로 타입 안전성을 확보한다.
 * DB에는 기존과 동일하게 'FIXED', 'PERCENT' 문자열로 저장되므로
 * 스키마 변경이나 데이터 마이그레이션이 필요 없다.
 */
public enum DiscountType {

    /** 정액 할인: discountValue만큼 차감 */
    FIXED,

    /** 정률 할인: 주문금액의 discountValue% 차감 (maxDiscount 상한 적용 가능) */
    PERCENT
}
