package com.shop.domain.review.dto;

import java.time.LocalDateTime;

/**
 * [Phase 22] 리뷰 목록 읽기 전용 모델 — CQRS 읽기 모델 분리.
 *
 * <p>v_review_list 뷰(reviews JOIN users)를 네이티브 SQL로 조회하여
 * JPA 엔티티 없이 불변 record에 직접 매핑한다.
 * 기존 Review 엔티티에 없던 username을 포함하여
 * 리뷰 목록에서 작성자명을 별도 쿼리 없이 표시할 수 있다.</p>
 */
public record ReviewListReadModel(
        Long reviewId,
        Long productId,
        Long userId,
        String username,
        Integer rating,
        String title,
        String content,
        Integer helpfulCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    /**
     * v_review_list 네이티브 SQL 결과(Object[])로부터 읽기 모델을 생성한다.
     *
     * @param columns v_review_list 뷰의 컬럼 순서와 일치하는 배열
     */
    public static ReviewListReadModel fromNativeRow(Object[] columns) {
        Long reviewId = ((Number) columns[0]).longValue();
        Long productId = ((Number) columns[1]).longValue();
        Long userId = ((Number) columns[2]).longValue();
        String username = (String) columns[3];
        Integer rating = columns[4] != null ? ((Number) columns[4]).intValue() : null;
        String title = (String) columns[5];
        String content = (String) columns[6];
        Integer helpfulCount = columns[7] != null ? ((Number) columns[7]).intValue() : 0;
        LocalDateTime createdAt = columns[8] != null
                ? ((java.sql.Timestamp) columns[8]).toLocalDateTime()
                : null;
        LocalDateTime updatedAt = columns[9] != null
                ? ((java.sql.Timestamp) columns[9]).toLocalDateTime()
                : null;

        return new ReviewListReadModel(
                reviewId, productId, userId, username,
                rating, title, content, helpfulCount,
                createdAt, updatedAt
        );
    }
}
