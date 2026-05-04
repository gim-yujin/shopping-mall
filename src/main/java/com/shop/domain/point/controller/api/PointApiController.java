package com.shop.domain.point.controller.api;

import com.shop.domain.point.dto.PointHistoryResponse;
import com.shop.domain.point.service.PointQueryService;
import com.shop.global.common.PageDefaults;
import com.shop.global.common.PagingParams;
import com.shop.global.dto.ApiResponse;
import com.shop.global.dto.PageResponse;
import com.shop.global.security.SecurityUtil;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 포인트 REST API 컨트롤러.
 *
 * <p>인증된 사용자의 포인트 이력 조회를 제공한다.
 * 기존 MyPageController(SSR)의 /mypage/points가 Thymeleaf 뷰를 반환하는 반면,
 * 이 컨트롤러는 JSON 응답을 반환한다.</p>
 */
@RestController
@RequestMapping("/api/v1/points")
public class PointApiController {

    private final PointQueryService pointQueryService;

    public PointApiController(PointQueryService pointQueryService) {
        this.pointQueryService = pointQueryService;
    }

    /**
     * 내 포인트 이력 조회.
     *
     * <p>적립(EARN), 사용(USE), 환불(REFUND), 만료(EXPIRE), 조정(ADJUST)
     * 등 모든 유형의 포인트 변동 내역을 최신순으로 반환한다.</p>
     */
    @GetMapping("/history")
    public ApiResponse<PageResponse<PointHistoryResponse>> getMyPointHistory(
            @RequestParam(defaultValue = "0") int page) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        int normalizedPage = PagingParams.normalizePage(page);
        return ApiResponse.ok(PageResponse.from(
                pointQueryService.getPointHistoriesByUser(userId,
                        PageRequest.of(normalizedPage, PageDefaults.DEFAULT_LIST_SIZE)),
                PointHistoryResponse::from));
    }
}
