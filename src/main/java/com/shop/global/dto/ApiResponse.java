package com.shop.global.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * [P1-6] REST API 표준 응답 래퍼.
 *
 * 모든 API 응답을 일관된 형식으로 감싸 클라이언트가 성공/실패를 동일한 구조로 처리할 수 있도록 한다.
 *
 * 성공 응답: { "success": true, "data": { ... } }
 * 실패 응답: { "success": false, "error": { "code": "...", "message": "..." } }
 *
 * @param <T> 응답 데이터 타입
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        T data,
        ErrorDetail error
) {
    public record ErrorDetail(String code, String message) {
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, null);
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(false, null, new ErrorDetail(code, message));
    }
}
