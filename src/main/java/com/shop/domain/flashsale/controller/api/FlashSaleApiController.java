package com.shop.domain.flashsale.controller.api;

import com.shop.domain.flashsale.dto.FlashSaleDetailResponse;
import com.shop.domain.flashsale.dto.FlashSaleListItemResponse;
import com.shop.domain.flashsale.service.FlashSaleQueryService;
import com.shop.global.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 플래시 세일 REST 읽기 API.
 *
 * <p>구매(POST /{id}/purchase)는 Phase 23-2에서 추가.</p>
 */
@RestController
@RequestMapping("/api/v1/flash-sales")
public class FlashSaleApiController {

    private final FlashSaleQueryService flashSaleQueryService;

    public FlashSaleApiController(FlashSaleQueryService flashSaleQueryService) {
        this.flashSaleQueryService = flashSaleQueryService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<FlashSaleListItemResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(flashSaleQueryService.listActiveAndUpcoming()));
    }

    @GetMapping("/{flashSaleId}")
    public ResponseEntity<ApiResponse<FlashSaleDetailResponse>> detail(@PathVariable Long flashSaleId) {
        return ResponseEntity.ok(ApiResponse.ok(flashSaleQueryService.getDetail(flashSaleId)));
    }
}
