package com.shop.global.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * [P1-6] 페이지네이션 응답 DTO.
 *
 * Spring Data의 Page 객체를 REST API 친화적인 구조로 변환한다.
 * Entity를 직접 노출하지 않고, mapper 함수를 통해 Response DTO로 변환한다.
 *
 * @param <T> 응답 항목 타입
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    /**
     * Spring Data Page를 PageResponse로 변환한다.
     *
     * @param page   원본 Page 객체
     * @param mapper Entity → Response DTO 변환 함수
     * @param <E>    엔티티 타입
     * @param <R>    응답 DTO 타입
     * @return 변환된 PageResponse
     */
    public static <E, R> PageResponse<R> from(Page<E> page, Function<E, R> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast()
        );
    }
}
