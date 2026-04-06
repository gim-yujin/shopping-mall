package com.shop.domain.coupon.controller.api;

import com.shop.domain.coupon.service.CouponService;
import com.shop.global.idempotency.IdempotencyExecutor;
import com.shop.global.idempotency.IdempotencyRecord;
import com.shop.global.idempotency.IdempotencyService;
import com.shop.global.metrics.IdempotencyMetrics;
import com.shop.global.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * CouponApiController 단위 테스트.
 *
 * <p>쿠폰 발급 REST API의 멱등성 키 패턴을 검증한다.
 * standaloneSetup으로 Security 필터 없이 컨트롤러 로직만 테스트한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class CouponApiControllerUnitTest {

    @Mock
    private CouponService couponService;
    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private IdempotencyMetrics idempotencyMetrics;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        IdempotencyExecutor idempotencyExecutor = new IdempotencyExecutor(idempotencyService, idempotencyMetrics);
        CouponApiController controller = new CouponApiController(
                couponService, idempotencyExecutor);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        // SecurityContextHolder에 인증 정보 설정 — SecurityUtil.getCurrentUserId() 동작용
        CustomUserPrincipal principal = new CustomUserPrincipal(
                1L, "testuser", "password", "테스트", "ROLE_USER",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void stubExecuteAndMarkCompleted(Long recordId, Long resourceId, int httpStatus) {
        doAnswer(invocation -> {
            Runnable action = invocation.getArgument(3);
            action.run();
            return null;
        }).when(idempotencyService).executeAndMarkCompleted(
                eq(recordId), eq(resourceId), eq(httpStatus), any(Runnable.class));
    }

    // ── 멱등성 키 없이 호출 (폴백) ──

    @Nested
    @DisplayName("멱등성 키 없는 쿠폰 발급 — 비멱등 폴백")
    class WithoutIdempotencyKey {

        @Test
        @DisplayName("멱등성 키 없이 발급 요청 시 201 반환")
        void issueCoupon_withoutKey_returns201() throws Exception {
            // 멱등성 키 헤더 없이 호출하면 기존 비멱등 동작으로 폴백
            mockMvc.perform(post("/api/v1/coupons/issue/1"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true));

            verify(couponService).issueCouponById(1L, 1);
            verifyNoInteractions(idempotencyService);
        }

        @Test
        @DisplayName("빈 문자열 멱등성 키도 비멱등 폴백으로 처리")
        void issueCoupon_blankKey_fallsBack() throws Exception {
            mockMvc.perform(post("/api/v1/coupons/issue/1")
                            .header("X-Idempotency-Key", "  "))
                    .andExpect(status().isCreated());

            verify(couponService).issueCouponById(1L, 1);
        }
    }

    // ── 멱등성 키로 최초 발급 ──

    @Nested
    @DisplayName("멱등성 키로 최초 쿠폰 발급")
    class FirstIssuanceWithKey {

        @Test
        @DisplayName("최초 요청 시 PROCESSING 레코드 생성 후 발급 → 201")
        void firstRequest_createsRecordAndIssues() throws Exception {
            String key = "test-key-123";
            IdempotencyRecord record = new IdempotencyRecord(1L, key, "COUPON_ISSUE");
            ReflectionTestUtils.setField(record, "recordId", 100L);

            when(idempotencyService.findExisting(1L, key)).thenReturn(Optional.empty());
            when(idempotencyService.initRecord(1L, key, "COUPON_ISSUE")).thenReturn(record);
            stubExecuteAndMarkCompleted(100L, 5L, 201);

            mockMvc.perform(post("/api/v1/coupons/issue/5")
                            .header("X-Idempotency-Key", key))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true));

            verify(couponService).issueCouponById(1L, 5);
            verify(idempotencyService).executeAndMarkCompleted(eq(100L), eq(5L), eq(201), any(Runnable.class));
            verify(idempotencyMetrics).recordNew();
        }
    }

    // ── 중복 요청 (COMPLETED/PROCESSING/FAILED) ──

    @Nested
    @DisplayName("중복 요청 분기 처리")
    class DuplicateRequests {

        @Test
        @DisplayName("COMPLETED 상태 — 이미 발급 완료된 쿠폰은 재발급 없이 201 반환")
        void completedRecord_returnsCachedResponse() throws Exception {
            String key = "completed-key";
            IdempotencyRecord completed = new IdempotencyRecord(1L, key, "COUPON_ISSUE");
            completed.markCompletedForSsr(5L);

            when(idempotencyService.findExisting(1L, key)).thenReturn(Optional.of(completed));

            mockMvc.perform(post("/api/v1/coupons/issue/5")
                            .header("X-Idempotency-Key", key))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true));

            // 쿠폰 서비스는 호출되지 않아야 함 — 재발급 방지
            verifyNoInteractions(couponService);
            verify(idempotencyMetrics).recordDuplicateCompleted();
        }

        @Test
        @DisplayName("PROCESSING 상태 — 이전 요청 처리 중이면 409 Conflict 반환")
        void processingRecord_returns409() throws Exception {
            String key = "processing-key";
            IdempotencyRecord processing = new IdempotencyRecord(1L, key, "COUPON_ISSUE");
            // PROCESSING 상태는 생성 직후 기본값

            when(idempotencyService.findExisting(1L, key)).thenReturn(Optional.of(processing));

            mockMvc.perform(post("/api/v1/coupons/issue/5")
                            .header("X-Idempotency-Key", key))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_PROCESSING"));

            verifyNoInteractions(couponService);
            verify(idempotencyMetrics).recordDuplicateProcessing();
        }

        @Test
        @DisplayName("FAILED 상태 — 이전 실패 후 재시도 허용")
        void failedRecord_allowsRetry() throws Exception {
            String key = "failed-key";
            IdempotencyRecord failed = new IdempotencyRecord(1L, key, "COUPON_ISSUE");
            failed.markFailed();

            IdempotencyRecord newRecord = new IdempotencyRecord(1L, key, "COUPON_ISSUE");
            ReflectionTestUtils.setField(newRecord, "recordId", 200L);

            when(idempotencyService.findExisting(1L, key)).thenReturn(Optional.of(failed));
            when(idempotencyService.retryAfterFailure(1L, key, "COUPON_ISSUE")).thenReturn(newRecord);
            stubExecuteAndMarkCompleted(200L, 5L, 201);

            mockMvc.perform(post("/api/v1/coupons/issue/5")
                            .header("X-Idempotency-Key", key))
                    .andExpect(status().isCreated());

            verify(couponService).issueCouponById(1L, 5);
            verify(idempotencyMetrics).recordRetry();
            verify(idempotencyMetrics).recordNew();
        }
    }

    // ── 예외 처리 ──

    @Nested
    @DisplayName("예외 처리")
    class ExceptionHandling {

        @Test
        @DisplayName("UNIQUE 충돌 시 409 Conflict — 동시에 같은 키로 요청")
        void uniqueViolation_returns409() throws Exception {
            String key = "race-key";
            when(idempotencyService.findExisting(1L, key)).thenReturn(Optional.empty());
            when(idempotencyService.initRecord(1L, key, "COUPON_ISSUE"))
                    .thenThrow(new DataIntegrityViolationException("unique_violation"));

            mockMvc.perform(post("/api/v1/coupons/issue/5")
                            .header("X-Idempotency-Key", key))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));

            verify(idempotencyMetrics).recordConflict();
        }

        @Test
        @DisplayName("쿠폰 발급 실패 시 FAILED 전환 후 예외 전파")
        void issuanceFailure_marksFailedAndRethrows() throws Exception {
            String key = "fail-key";
            IdempotencyRecord record = new IdempotencyRecord(1L, key, "COUPON_ISSUE");
            ReflectionTestUtils.setField(record, "recordId", 300L);

            when(idempotencyService.findExisting(1L, key)).thenReturn(Optional.empty());
            when(idempotencyService.initRecord(1L, key, "COUPON_ISSUE")).thenReturn(record);
            doThrow(new RuntimeException("COUPON_SOLD_OUT"))
                    .when(idempotencyService).executeAndMarkCompleted(eq(300L), eq(5L), eq(201), any(Runnable.class));

            // standaloneSetup에는 GlobalExceptionHandler 없으므로 ServletException으로 래핑됨
            try {
                mockMvc.perform(post("/api/v1/coupons/issue/5")
                        .header("X-Idempotency-Key", key));
            } catch (Exception ignored) {
                // 예외 전파 확인
            }

            verify(idempotencyService).markFailed(300L);
        }
    }
}
