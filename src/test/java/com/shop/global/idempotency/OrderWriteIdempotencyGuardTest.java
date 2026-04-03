package com.shop.global.idempotency;

import com.shop.global.exception.BusinessException;
import com.shop.global.metrics.IdempotencyMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderWriteIdempotencyGuardTest {

    @Mock
    private IdempotencyMetrics idempotencyMetrics;

    @Nested
    @DisplayName("호환 모드 (requireOrderWriteKey=false)")
    class CompatibilityMode {

        @Test
        @DisplayName("키 누락 시 메트릭만 기록하고 예외 없이 통과")
        void handleMissingKey_compatibilityMode_doesNotThrow() {
            OrderWriteIdempotencyGuard guard =
                    new OrderWriteIdempotencyGuard(idempotencyMetrics, false);

            guard.handleMissingKey("WEB", "CREATE_ORDER", 1L);

            verify(idempotencyMetrics).recordMissingKey("WEB", "CREATE_ORDER");
        }
    }

    @Nested
    @DisplayName("강제 모드 (requireOrderWriteKey=true)")
    class EnforcementMode {

        @Test
        @DisplayName("키 누락 시 BusinessException 발생")
        void handleMissingKey_enforcementMode_throwsBusinessException() {
            OrderWriteIdempotencyGuard guard =
                    new OrderWriteIdempotencyGuard(idempotencyMetrics, true);

            assertThatThrownBy(() -> guard.handleMissingKey("API", "CANCEL_ORDER", 2L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("멱등성 키가 필요합니다");

            verify(idempotencyMetrics).recordMissingKey("API", "CANCEL_ORDER");
        }
    }
}
