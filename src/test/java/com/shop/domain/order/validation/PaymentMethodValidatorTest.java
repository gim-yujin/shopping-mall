package com.shop.domain.order.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentMethodValidatorTest {

    private final PaymentMethodValidator validator = new PaymentMethodValidator();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t"})
    @DisplayName("null 또는 빈 문자열은 유효하다 (별도 @NotBlank에 위임)")
    void isValid_nullOrBlank_returnsTrue(String value) {
        assertThat(validator.isValid(value, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CARD", "BANK", "KAKAO", "NAVER", "PAYCO"})
    @DisplayName("유효한 결제수단 코드는 통과")
    void isValid_validCode_returnsTrue(String value) {
        assertThat(validator.isValid(value, null)).isTrue();
    }

    @Test
    @DisplayName("존재하지 않는 결제수단 코드는 거부")
    void isValid_invalidCode_returnsFalse() {
        assertThat(validator.isValid("BITCOIN", null)).isFalse();
    }

    @Test
    @DisplayName("소문자 코드도 유효 (대소문자 무관)")
    void isValid_lowercaseCode_returnsTrue() {
        assertThat(validator.isValid("card", null)).isTrue();
    }
}
