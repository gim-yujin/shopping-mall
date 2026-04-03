package com.shop.domain.coupon.controller.api;

import com.shop.domain.coupon.service.CouponService;
import com.shop.global.dto.ApiResponse;
import com.shop.global.idempotency.IdempotencyRecord;
import com.shop.global.idempotency.IdempotencyService;
import com.shop.global.metrics.IdempotencyMetrics;
import com.shop.global.security.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * [Phase 14] 쿠폰 발급 REST API 컨트롤러 (멱등성 보장).
 *
 * <h3>왜 쿠폰 발급에 멱등성이 필요한가?</h3>
 * <p>선착순 쿠폰 이벤트에서 수천 명이 동시에 발급 버튼을 누르면
 * 네트워크 타임아웃이 빈번하게 발생한다. 클라이언트가 응답을 받지 못하고
 * 재시도하면 다음 문제가 발생할 수 있다:</p>
 * <ul>
 *   <li>ALREADY_ISSUED 에러 — 실제로는 성공했지만 사용자는 실패로 인식</li>
 *   <li>COUPON_SOLD_OUT 에러 — 이미 수량이 차감되었으나 UserCoupon이 생성되기 전에
 *       타임아웃 발생 시 수량만 감소하는 정합성 문제</li>
 * </ul>
 *
 * <p>멱등성 키를 적용하면 첫 번째 발급 성공 후 재시도 시
 * COMPLETED 상태의 캐시된 응답을 반환하여 위 문제를 해결한다.</p>
 *
 * <h3>기존 CouponController(SSR)와의 역할 분담</h3>
 * <ul>
 *   <li>CouponController — Thymeleaf 폼 기반 SSR, 리다이렉트 응답</li>
 *   <li>CouponApiController — REST API, JSON 응답, 멱등성 키 지원</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/coupons")
public class CouponApiController {

    private static final Logger log = LoggerFactory.getLogger(CouponApiController.class);

    private final CouponService couponService;
    private final IdempotencyService idempotencyService;
    private final IdempotencyMetrics idempotencyMetrics;

    public CouponApiController(CouponService couponService,
                                IdempotencyService idempotencyService,
                                IdempotencyMetrics idempotencyMetrics) {
        this.couponService = couponService;
        this.idempotencyService = idempotencyService;
        this.idempotencyMetrics = idempotencyMetrics;
    }

    /**
     * 쿠폰 발급 (쿠폰 ID 기반, 멱등성 보장).
     *
     * <p>선착순 이벤트에서 X-Idempotency-Key 헤더를 전달하면
     * 동일 요청의 이중 발급을 방지한다. 헤더 없이 호출하면
     * 기존 비멱등 동작으로 폴백한다.</p>
     *
     * <p><b>resourceType:</b> COUPON_ISSUE — 쿠폰 발급 고유 타입.
     * ORDER와 구분하여 동일한 멱등성 키라도 리소스 타입이 다르면
     * 별개의 요청으로 처리된다. 단, UNIQUE 제약은 (userId, key)이므로
     * 클라이언트는 요청마다 고유한 키를 생성해야 한다.</p>
     */
    @PostMapping("/issue/{couponId}")
    public ResponseEntity<ApiResponse<Void>> issueCoupon(
            @PathVariable Integer couponId,
            @RequestHeader(name = "X-Idempotency-Key", required = false) String idempotencyKey) {

        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();

        // 멱등성 키가 없으면 기존 비멱등 동작으로 폴백
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            couponService.issueCouponById(userId, couponId);
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok());
        }

        // ── [Phase 14] 멱등성 키 패턴 적용 ────────────────────

        idempotencyService.validateKey(idempotencyKey);

        // 1단계: 기존 레코드 확인
        Optional<IdempotencyRecord> existing = idempotencyService.findExisting(userId, idempotencyKey);
        if (existing.isPresent()) {
            IdempotencyRecord prev = existing.get();

            if (prev.isCompleted()) {
                // 이미 발급 완료 — 재발급 없이 성공 응답 반환
                idempotencyMetrics.recordDuplicateCompleted();
                log.info("쿠폰 멱등성 키 중복 (COMPLETED) - userId={}, key={}, couponId={}",
                        userId, idempotencyKey, couponId);
                return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok());
            }

            if (prev.isProcessing()) {
                idempotencyMetrics.recordDuplicateProcessing();
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiResponse.error("IDEMPOTENCY_PROCESSING",
                                "이전 쿠폰 발급 요청이 처리 중입니다. 잠시 후 다시 시도해주세요."));
            }

            // FAILED — 재시도 허용
            idempotencyMetrics.recordRetry();
        }

        // 2단계: PROCESSING 레코드 생성
        IdempotencyRecord record;
        boolean isRetry = existing.isPresent();
        try {
            record = isRetry
                    ? idempotencyService.retryAfterFailure(userId, idempotencyKey, "COUPON_ISSUE")
                    : idempotencyService.initRecord(userId, idempotencyKey, "COUPON_ISSUE");
        } catch (DataIntegrityViolationException e) {
            idempotencyMetrics.recordConflict();
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error("IDEMPOTENCY_CONFLICT",
                            "동일한 요청이 처리 중입니다. 잠시 후 다시 시도해주세요."));
        }

        idempotencyMetrics.recordNew();

        // 3단계: 쿠폰 발급 실행
        try {
            idempotencyService.executeAndMarkCompleted(
                    record.getRecordId(),
                    couponId.longValue(),
                    HttpStatus.CREATED.value(),
                    () -> couponService.issueCouponById(userId, couponId));
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok());
        } catch (Exception e) {
            idempotencyService.markFailed(record.getRecordId());
            throw e;
        }
    }
}
