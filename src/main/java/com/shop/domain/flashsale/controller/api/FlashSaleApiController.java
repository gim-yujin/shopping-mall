package com.shop.domain.flashsale.controller.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.domain.flashsale.dto.FlashSaleDetailResponse;
import com.shop.domain.flashsale.dto.FlashSaleListItemResponse;
import com.shop.domain.flashsale.dto.FlashSalePurchaseResponse;
import com.shop.domain.flashsale.service.FlashSaleCommandService;
import com.shop.domain.flashsale.service.FlashSaleQueryService;
import com.shop.global.dto.ApiResponse;
import com.shop.global.idempotency.IdempotencyExecutor;
import com.shop.global.idempotency.IdempotencyRecord;
import com.shop.global.security.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.function.Function;

/**
 * 플래시 세일 REST API.
 *
 * <p>GET: 목록·상세 조회 (Phase 23-1).<br>
 * POST: CAS 예약 기반 구매 (Phase 23-2).</p>
 */
@RestController
@RequestMapping("/api/v1/flash-sales")
public class FlashSaleApiController {

    private static final Logger log = LoggerFactory.getLogger(FlashSaleApiController.class);

    private final FlashSaleQueryService flashSaleQueryService;
    private final FlashSaleCommandService flashSaleCommandService;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ObjectMapper objectMapper;

    public FlashSaleApiController(FlashSaleQueryService flashSaleQueryService,
                                  FlashSaleCommandService flashSaleCommandService,
                                  IdempotencyExecutor idempotencyExecutor,
                                  ObjectMapper objectMapper) {
        this.flashSaleQueryService = flashSaleQueryService;
        this.flashSaleCommandService = flashSaleCommandService;
        this.idempotencyExecutor = idempotencyExecutor;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<FlashSaleListItemResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(flashSaleQueryService.listActiveAndUpcoming()));
    }

    @GetMapping("/{flashSaleId}")
    public ResponseEntity<ApiResponse<FlashSaleDetailResponse>> detail(@PathVariable Long flashSaleId) {
        return ResponseEntity.ok(ApiResponse.ok(flashSaleQueryService.getDetail(flashSaleId)));
    }

    /**
     * [Phase 23-2] 플래시 세일 구매.
     *
     * <p>{@code X-Idempotency-Key}는 필수다. 누락 시 Spring이 자동으로 400을 반환한다.
     * 동일 키 재시도는 최초 성공 응답을 재사용한다.</p>
     */
    @PostMapping("/{flashSaleId}/items/{flashSaleItemId}/purchase")
    public ResponseEntity<ApiResponse<FlashSalePurchaseResponse>> purchase(
            @PathVariable Long flashSaleId,
            @PathVariable Long flashSaleItemId,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey) {

        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();

        return idempotencyExecutor.execute(
                userId, idempotencyKey, "FLASH_SALE", HttpStatus.CREATED.value(),
                this::deserializeCachedResponse,
                () -> flashSaleCommandService.purchase(flashSaleId, flashSaleItemId, userId),
                FlashSalePurchaseResponse::orderId,
                Function.identity());
    }

    private ResponseEntity<ApiResponse<FlashSalePurchaseResponse>> deserializeCachedResponse(
            IdempotencyRecord record) {
        if (record.getResponseBody() != null) {
            try {
                ApiResponse<FlashSalePurchaseResponse> cached = objectMapper.readValue(
                        record.getResponseBody(),
                        objectMapper.getTypeFactory().constructParametricType(
                                ApiResponse.class, FlashSalePurchaseResponse.class));
                return ResponseEntity.status(record.getHttpStatus()).body(cached);
            } catch (JsonProcessingException e) {
                log.warn("캐시된 멱등성 응답 역직렬화 실패. recordId={}", record.getRecordId(), e);
            }
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok((FlashSalePurchaseResponse) null));
    }
}
