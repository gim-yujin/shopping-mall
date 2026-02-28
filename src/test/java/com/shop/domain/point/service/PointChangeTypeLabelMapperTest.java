package com.shop.domain.point.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PointChangeTypeLabelMapperTest {

    @Test
    void normalize_shouldUppercaseAndUnifySeparators() {
        assertThat(PointChangeTypeLabelMapper.normalize(" earn ")).isEqualTo("EARN");
        assertThat(PointChangeTypeLabelMapper.normalize("re-fund")).isEqualTo("RE_FUND");
        assertThat(PointChangeTypeLabelMapper.normalize("use points")).isEqualTo("USE_POINTS");
    }

    @Test
    void toKoreanLabel_shouldMapKnownTypes() {
        assertThat(PointChangeTypeLabelMapper.toKoreanLabel("EARN")).isEqualTo("적립");
        assertThat(PointChangeTypeLabelMapper.toKoreanLabel("use")).isEqualTo("사용");
        assertThat(PointChangeTypeLabelMapper.toKoreanLabel(" refund ")).isEqualTo("환불");
        assertThat(PointChangeTypeLabelMapper.toKoreanLabel("EXPIRE")).isEqualTo("만료");
        assertThat(PointChangeTypeLabelMapper.toKoreanLabel("ADJUST")).isEqualTo("조정");
    }

    @Test
    void toKoreanLabel_shouldReturnEtcForUnknownOrNull() {
        assertThat(PointChangeTypeLabelMapper.toKoreanLabel("manual")).isEqualTo("기타");
        assertThat(PointChangeTypeLabelMapper.toKoreanLabel(null)).isEqualTo("기타");
    }
}
