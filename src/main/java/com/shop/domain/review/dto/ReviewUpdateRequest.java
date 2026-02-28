package com.shop.domain.review.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * [3.7] 리뷰 수정 요청 DTO.
 *
 * 생성과 달리 productId·orderItemId는 변경 불가이므로 포함하지 않는다.
 * rating, title, content만 수정 가능하다.
 */
public record ReviewUpdateRequest(
    @Min(value = 1, message = "평점은 1점 이상이어야 합니다.")
    @Max(value = 5, message = "평점은 5점 이하여야 합니다.")
    int rating,
    @Size(max = ReviewCreateRequest.TITLE_MAX_LENGTH, message = "리뷰 제목은 200자 이하로 입력해주세요.")
    @Pattern(regexp = "^(?!\\s*$).+", message = "리뷰 제목은 공백만 입력할 수 없습니다.")
    String title,
    @Size(max = ReviewCreateRequest.CONTENT_MAX_LENGTH, message = "리뷰 내용은 5,000자 이하로 입력해주세요.")
    @Pattern(regexp = "^(?!\\s*$).+", message = "리뷰 내용은 공백만 입력할 수 없습니다.")
    String content
) {}
