package com.shop.global.metrics;

import com.shop.global.backpressure.BackpressureDetector;
import com.shop.global.backpressure.PressureLevel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * BackpressureMeterBinder 테스트.
 *
 * <p>NORMAL/ELEVATED/CRITICAL 세 가지 상태에서 게이지 값을 검증한다.
 * 기존 테스트에서 CRITICAL 상태와 shouldShedNonCritical=true 브랜치가 미커버.</p>
 */
@ExtendWith(MockitoExtension.class)
class BackpressureMeterBinderTest {

    @Mock private BackpressureDetector detector;

    @Test
    @DisplayName("NORMAL — level=0, shedding=0.0")
    void bindTo_normal_levelZeroSheddingInactive() {
        when(detector.getPressureLevel()).thenReturn(PressureLevel.NORMAL);
        when(detector.shouldShedNonCritical()).thenReturn(false);

        MeterRegistry registry = new SimpleMeterRegistry();
        new BackpressureMeterBinder(detector).bindTo(registry);

        assertThat(registry.get("shop.backpressure.level").gauge().value()).isEqualTo(0.0);
        assertThat(registry.get("shop.backpressure.shedding.active").gauge().value()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("ELEVATED — level=1, shedding=0.0")
    void bindTo_elevated_levelOneSheddingInactive() {
        when(detector.getPressureLevel()).thenReturn(PressureLevel.ELEVATED);
        when(detector.shouldShedNonCritical()).thenReturn(false);

        MeterRegistry registry = new SimpleMeterRegistry();
        new BackpressureMeterBinder(detector).bindTo(registry);

        assertThat(registry.get("shop.backpressure.level").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("shop.backpressure.shedding.active").gauge().value()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("CRITICAL — level=2, shedding=1.0")
    void bindTo_critical_levelTwoSheddingActive() {
        when(detector.getPressureLevel()).thenReturn(PressureLevel.CRITICAL);
        when(detector.shouldShedNonCritical()).thenReturn(true);

        MeterRegistry registry = new SimpleMeterRegistry();
        new BackpressureMeterBinder(detector).bindTo(registry);

        assertThat(registry.get("shop.backpressure.level").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("shop.backpressure.shedding.active").gauge().value()).isEqualTo(1.0);
    }
}
