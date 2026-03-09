package com.shop.domain.review.controller;

import com.shop.domain.review.dto.ReviewCreateRequest;
import com.shop.domain.review.dto.ReviewUpdateRequest;
import com.shop.domain.review.entity.Review;
import com.shop.domain.review.service.ReviewService;
import com.shop.global.exception.BusinessException;
import com.shop.global.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ReviewController 보충 단위 테스트.
 *
 * <p>기존 ReviewControllerUnitTest는 createReview의 productId 누락 케이스만 커버한다.
 * 이 테스트는 나머지 모든 경로를 커버한다:
 * createReview 성공/검증실패(productId있음)/비즈니스예외,
 * editReviewForm, updateReview 성공/실패,
 * markHelpful 성공(true/false)/실패, deleteReview 성공/실패.</p>
 *
 * <p>커버리지 목표: 15% → 90%+ (49라인 중 대부분 커버)</p>
 */
@ExtendWith(MockitoExtension.class)
class ReviewControllerSupplementaryUnitTest {

    private static final Long USER_ID = 1L;

    @Mock
    private ReviewService reviewService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ReviewController controller = new ReviewController(reviewService);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setValidator(validator)
                .build();

        CustomUserPrincipal principal = new CustomUserPrincipal(
                USER_ID, "tester", "encoded", "테스터", "ROLE_USER",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── 픽스처 ──────────────────────────────────────────────────

    private Review createReview() {
        Review review = new Review(200L, USER_ID, 300L, 5, "좋아요", "매우 만족합니다");
        ReflectionTestUtils.setField(review, "reviewId", 10L);
        return review;
    }

    // ── POST /reviews — 리뷰 생성 ──────────────────────────────

    @Nested
    @DisplayName("POST /reviews — 리뷰 생성")
    class CreateReviewTests {

        @Test
        @DisplayName("정상 생성 시 상품 상세로 리다이렉트하고 성공 메시지를 전달한다")
        void createReview_success() throws Exception {
            // given: 유효한 리뷰 요청
            Review review = createReview();
            when(reviewService.createReview(eq(USER_ID), any(ReviewCreateRequest.class))).thenReturn(review);

            // when & then: 상품 상세 페이지로 리다이렉트
            mockMvc.perform(post("/reviews")
                            .param("productId", "200")
                            .param("orderItemId", "300")
                            .param("rating", "5")
                            .param("title", "좋아요")
                            .param("content", "매우 만족합니다"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/products/200"))
                    .andExpect(flash().attribute("successMessage", "리뷰가 등록되었습니다."));
        }

        @Test
        @DisplayName("검증 실패(productId 있음) 시 상품 상세로 리다이렉트하고 에러 메시지를 전달한다")
        void createReview_validationError_withProductId_redirectsToProduct() throws Exception {
            // given: title이 공백만 → @Pattern 위반, productId는 존재
            // 이 경로는 기존 테스트(productId 누락)와 다른 분기를 커버한다
            mockMvc.perform(post("/reviews")
                            .param("productId", "200")
                            .param("orderItemId", "300")
                            .param("rating", "5")
                            .param("title", "   ")
                            .param("content", "내용"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/products/200"))
                    .andExpect(flash().attributeExists("errorMessage"));

            verify(reviewService, never()).createReview(anyLong(), any());
        }

        @Test
        @DisplayName("BusinessException 발생 시 에러 메시지를 전달한다")
        void createReview_businessException() throws Exception {
            // given: 이미 리뷰를 작성한 주문 항목 등 비즈니스 예외
            when(reviewService.createReview(eq(USER_ID), any(ReviewCreateRequest.class)))
                    .thenThrow(new BusinessException("REVIEW_ALREADY_EXISTS", "이미 리뷰를 작성하셨습니다."));

            // when & then
            mockMvc.perform(post("/reviews")
                            .param("productId", "200")
                            .param("orderItemId", "300")
                            .param("rating", "4")
                            .param("title", "제목")
                            .param("content", "내용"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/products/200"))
                    .andExpect(flash().attribute("errorMessage", "이미 리뷰를 작성하셨습니다."));
        }
    }

    // ── GET /reviews/{reviewId}/edit — 수정 폼 ─────────────────

    @Nested
    @DisplayName("GET /reviews/{reviewId}/edit — 수정 폼")
    class EditReviewFormTests {

        @Test
        @DisplayName("수정 폼에 기존 리뷰 정보가 채워진다")
        void editReviewForm_rendersEditView() throws Exception {
            // given
            Review review = createReview();
            when(reviewService.getReviewForEdit(10L, USER_ID)).thenReturn(review);

            // when & then
            mockMvc.perform(get("/reviews/{reviewId}/edit", 10L))
                    .andExpect(status().isOk())
                    .andExpect(view().name("review/edit"))
                    .andExpect(model().attributeExists("review"));
        }
    }

    // ── POST /reviews/{reviewId}/edit — 수정 처리 ──────────────

    @Nested
    @DisplayName("POST /reviews/{reviewId}/edit — 수정 처리")
    class UpdateReviewTests {

        @Test
        @DisplayName("정상 수정 시 상품 상세로 리다이렉트하고 성공 메시지를 전달한다")
        void updateReview_success() throws Exception {
            // given
            Review review = createReview();
            when(reviewService.updateReview(eq(10L), eq(USER_ID), any(ReviewUpdateRequest.class)))
                    .thenReturn(review);

            // when & then
            mockMvc.perform(post("/reviews/{reviewId}/edit", 10L)
                            .param("rating", "4")
                            .param("title", "수정된 제목")
                            .param("content", "수정된 내용")
                            .param("productId", "200"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/products/200"))
                    .andExpect(flash().attribute("successMessage", "리뷰가 수정되었습니다."));
        }

        @Test
        @DisplayName("검증 실패 시 수정 폼으로 리다이렉트한다")
        void updateReview_validationError() throws Exception {
            // given: rating 0 → @Min(1) 위반
            mockMvc.perform(post("/reviews/{reviewId}/edit", 10L)
                            .param("rating", "0")
                            .param("title", "제목")
                            .param("content", "내용")
                            .param("productId", "200"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/reviews/10/edit"))
                    .andExpect(flash().attributeExists("errorMessage"));

            verify(reviewService, never()).updateReview(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("BusinessException 발생 시 에러 메시지를 전달한다")
        void updateReview_businessException() throws Exception {
            // given
            when(reviewService.updateReview(eq(10L), eq(USER_ID), any(ReviewUpdateRequest.class)))
                    .thenThrow(new BusinessException("REVIEW_NOT_OWNER", "본인 리뷰만 수정할 수 있습니다."));

            // when & then
            mockMvc.perform(post("/reviews/{reviewId}/edit", 10L)
                            .param("rating", "4")
                            .param("title", "수정")
                            .param("content", "내용")
                            .param("productId", "200"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/products/200"))
                    .andExpect(flash().attribute("errorMessage", "본인 리뷰만 수정할 수 있습니다."));
        }
    }

    // ── POST /reviews/{reviewId}/helpful — 도움이 돼요 ─────────

    @Nested
    @DisplayName("POST /reviews/{reviewId}/helpful — 도움이 돼요")
    class MarkHelpfulTests {

        @Test
        @DisplayName("도움이 돼요 등록 시 성공 메시지를 전달한다")
        void markHelpful_on() throws Exception {
            // given
            when(reviewService.markHelpful(10L, USER_ID)).thenReturn(true);

            // when & then
            mockMvc.perform(post("/reviews/{reviewId}/helpful", 10L)
                            .param("productId", "200"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/products/200"))
                    .andExpect(flash().attribute("successMessage", "도움이 됐어요를 눌렀습니다."));
        }

        @Test
        @DisplayName("도움이 돼요 해제 시 취소 메시지를 전달한다")
        void markHelpful_off() throws Exception {
            // given
            when(reviewService.markHelpful(10L, USER_ID)).thenReturn(false);

            // when & then
            mockMvc.perform(post("/reviews/{reviewId}/helpful", 10L)
                            .param("productId", "200"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/products/200"))
                    .andExpect(flash().attribute("successMessage", "도움이 됐어요를 취소했습니다."));
        }

        @Test
        @DisplayName("BusinessException 발생 시 에러 메시지를 전달한다")
        void markHelpful_businessException() throws Exception {
            // given
            when(reviewService.markHelpful(10L, USER_ID))
                    .thenThrow(new BusinessException("SELF_HELPFUL", "본인 리뷰에는 도움이 돼요를 누를 수 없습니다."));

            // when & then
            mockMvc.perform(post("/reviews/{reviewId}/helpful", 10L)
                            .param("productId", "200"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/products/200"))
                    .andExpect(flash().attribute("errorMessage", "본인 리뷰에는 도움이 돼요를 누를 수 없습니다."));
        }
    }

    // ── POST /reviews/{reviewId}/delete — 삭제 ─────────────────

    @Nested
    @DisplayName("POST /reviews/{reviewId}/delete — 리뷰 삭제")
    class DeleteReviewTests {

        @Test
        @DisplayName("삭제 성공 시 상품 상세로 리다이렉트하고 성공 메시지를 전달한다")
        void deleteReview_success() throws Exception {
            // given
            doNothing().when(reviewService).deleteReview(10L, USER_ID);

            // when & then
            mockMvc.perform(post("/reviews/{reviewId}/delete", 10L)
                            .param("productId", "200"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/products/200"))
                    .andExpect(flash().attribute("successMessage", "리뷰가 삭제되었습니다."));
        }

        @Test
        @DisplayName("삭제 실패 시 에러 메시지를 전달한다")
        void deleteReview_businessException() throws Exception {
            // given
            doThrow(new BusinessException("REVIEW_NOT_OWNER", "본인 리뷰만 삭제할 수 있습니다."))
                    .when(reviewService).deleteReview(10L, USER_ID);

            // when & then
            mockMvc.perform(post("/reviews/{reviewId}/delete", 10L)
                            .param("productId", "200"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/products/200"))
                    .andExpect(flash().attribute("errorMessage", "본인 리뷰만 삭제할 수 있습니다."));
        }
    }
}
