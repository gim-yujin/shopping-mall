package com.shop.domain.review.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ReviewCreateRequest(
    @NotNull(message = "상품 정보가 누락되었습니다.")
    Long productId,
    @NotNull(message = "주문 항목 정보가 누락되었습니다.")
    Long orderItemId,
    @Min(value = 1, message = "평점은 1점 이상이어야 합니다.")
    @Max(value = 5, message = "평점은 5점 이하여야 합니다.")
    int rating,
    @Size(max = ReviewCreateRequest.TITLE_MAX_LENGTH, message = "리뷰 제목은 200자 이하로 입력해주세요.")
    @Pattern(regexp = "^(?!\\s*$).+", message = "리뷰 제목은 공백만 입력할 수 없습니다.")
    String title,
    @Size(max = ReviewCreateRequest.CONTENT_MAX_LENGTH, message = "리뷰 내용은 5,000자 이하로 입력해주세요.")
    @Pattern(regexp = "^(?!\\s*$).+", message = "리뷰 내용은 공백만 입력할 수 없습니다.")
    String content
) {
    public static final int TITLE_MAX_LENGTH = 200;
    public static final int CONTENT_MAX_LENGTH = 5000;
}
