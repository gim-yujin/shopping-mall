package com.shop.domain.inventory.controller.api;

import com.shop.domain.inventory.dto.InventoryHistoryResponse;
import com.shop.domain.inventory.dto.StockAdjustRequest;
import com.shop.domain.inventory.service.InventoryService;
import com.shop.global.common.PageDefaults;
import com.shop.global.common.PagingParams;
import com.shop.global.dto.ApiResponse;
import com.shop.global.dto.PageResponse;
import com.shop.global.security.SecurityUtil;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 재고 관리 REST API 컨트롤러 (관리자 전용).
 *
 * <p>재고 변경 이력 조회 및 수동 재고 조정 기능을 제공한다.
 * SecurityConfig에서 /api/v1/admin/** 경로를 hasRole("ADMIN")으로 설정한다.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/inventory")
public class InventoryApiController {

    private final InventoryService inventoryService;

    public InventoryApiController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /**
     * 상품별 재고 변경 이력 조회.
     */
    @GetMapping("/{productId}/history")
    public ApiResponse<PageResponse<InventoryHistoryResponse>> getHistory(
            @PathVariable Long productId,
            @RequestParam(defaultValue = "0") int page) {
        int normalizedPage = PagingParams.normalizePage(page);
        return ApiResponse.ok(PageResponse.from(
                inventoryService.getHistory(productId,
                        PageRequest.of(normalizedPage, PageDefaults.DEFAULT_LIST_SIZE)),
                InventoryHistoryResponse::from));
    }

    /**
     * 수동 재고 조정.
     *
     * <p>양수 amount는 입고, 음수 amount는 출고를 의미한다.
     * 변경 사유(reason)는 이력에 기록된다.</p>
     *
     * @param productId 상품 ID
     * @param request   조정 수량 및 사유
     */
    @PostMapping("/{productId}/adjust")
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> adjustStock(
            @PathVariable Long productId,
            @Valid @RequestBody StockAdjustRequest request) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        inventoryService.adjustStock(productId, request.amount(), request.reason(), userId);
        return ApiResponse.ok();
    }
}
