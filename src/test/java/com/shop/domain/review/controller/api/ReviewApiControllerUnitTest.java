package com.shop.domain.review.controller.api;

import com.shop.domain.review.dto.ReviewCreateRequest;
import com.shop.domain.review.entity.Review;
import com.shop.domain.review.service.ReviewService;
import com.shop.global.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ReviewApiController 단위 테스트.
 *
 * <p>리뷰 API는 공개(GET 리뷰목록)와 인증 필요(작성/삭제/도움이돼요)가 혼재하지만,
 * standaloneSetup에서는 Spring Security 필터 체인이 없으므로
 * 인증 검증은 SecurityIntegrationTest에서 별도로 검증한다.
 * 여기서는 컨트롤러 로직(파라미터 바인딩, 서비스 호출, 응답 구조)에 집중한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class ReviewApiControllerUnitTest {

    private static final Long USER_ID = 1L;

    @Mock
    private ReviewService reviewService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ReviewApiController controller = new ReviewApiController(reviewService);

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

    private Review createReview(Long reviewId) {
        Review review = new Review(200L, USER_ID, 300L, 5, "좋아요", "매우 만족합니다");
        ReflectionTestUtils.setField(review, "reviewId", reviewId);
        return review;
    }

    // ── GET /api/v1/products/{productId}/reviews ────────────────

    @Nested
    @DisplayName("GET /api/v1/products/{productId}/reviews — 리뷰 목록")
    class GetProductReviewsTests {

        @Test
        @DisplayName("리뷰가 있으면 페이징된 목록을 반환한다")
        void getProductReviews_returnsPagedList() throws Exception {
            // given: 상품 200번에 리뷰 1건 존재
            Review review = createReview(10L);
            Page<Review> page = new PageImpl<>(List.of(review), PageRequest.of(0, 10), 1);
            when(reviewService.getProductReviews(eq(200L), any(PageRequest.class))).thenReturn(page);

            // when & then: PageResponse 구조 확인
            mockMvc.perform(get("/api/v1/products/{productId}/reviews", 200L)
                            .param("page", "0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.content[0].reviewId").value(10))
                    .andExpect(jsonPath("$.data.content[0].rating").value(5))
                    .andExpect(jsonPath("$.data.totalElements").value(1));
        }

        @Test
        @DisplayName("리뷰가 없으면 빈 목록을 반환한다")
        void getProductReviews_empty() throws Exception {
            // given
            Page<Review> page = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 10), 0);
            when(reviewService.getProductReviews(eq(200L), any(PageRequest.class))).thenReturn(page);

            // when & then
            mockMvc.perform(get("/api/v1/products/{productId}/reviews", 200L))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isEmpty());
        }
    }

    // ── POST /api/v1/reviews ────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/reviews — 리뷰 작성")
    class CreateReviewTests {

        @Test
        @DisplayName("정상 요청 시 201 Created와 리뷰 정보를 반환한다")
        void createReview_success_returns201() throws Exception {
            // given
            Review review = createReview(10L);
            when(reviewService.createReview(eq(USER_ID), any(ReviewCreateRequest.class))).thenReturn(review);

            String requestBody = """
                    {
                        "productId": 200,
                        "orderItemId": 300,
                        "rating": 5,
                        "title": "좋아요",
                        "content": "매우 만족합니다"
                    }
                    """;

            // when & then
            mockMvc.perform(post("/api/v1/reviews")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.reviewId").value(10))
                    .andExpect(jsonPath("$.data.rating").value(5));
        }

        @Test
        @DisplayName("평점 범위 초과(6점) 시 400 Bad Request를 반환한다")
        void createReview_ratingOutOfRange_returns400() throws Exception {
            // given: rating 6 → @Max(5) 위반
            String requestBody = """
                    {
                        "productId": 200,
                        "orderItemId": 300,
                        "rating": 6,
                        "title": "좋아요",
                        "content": "매우 만족합니다"
                    }
                    """;

            // when & then
            mockMvc.perform(post("/api/v1/reviews")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest());

            verify(reviewService, never()).createReview(anyLong(), any());
        }

        @Test
        @DisplayName("productId 누락 시 400 Bad Request를 반환한다")
        void createReview_missingProductId_returns400() throws Exception {
            // given: productId 없음 → @NotNull 위반
            String requestBody = """
                    {
                        "orderItemId": 300,
                        "rating": 4,
                        "title": "괜찮아요",
                        "content": "보통입니다"
                    }
                    """;

            mockMvc.perform(post("/api/v1/reviews")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest());

            verify(reviewService, never()).createReview(anyLong(), any());
        }
    }

    // ── DELETE /api/v1/reviews/{reviewId} ────────────────────────

    @Nested
    @DisplayName("DELETE /api/v1/reviews/{reviewId} — 리뷰 삭제")
    class DeleteReviewTests {

        @Test
        @DisplayName("삭제 성공 시 200 OK를 반환한다")
        void deleteReview_success() throws Exception {
            // given
            doNothing().when(reviewService).deleteReview(10L, USER_ID);

            // when & then
            mockMvc.perform(delete("/api/v1/reviews/{reviewId}", 10L))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(reviewService).deleteReview(10L, USER_ID);
        }
    }

    // ── POST /api/v1/reviews/{reviewId}/helpful ─────────────────

    @Nested
    @DisplayName("POST /api/v1/reviews/{reviewId}/helpful — 도움이 돼요 토글")
    class ToggleHelpfulTests {

        @Test
        @DisplayName("도움이 돼요 등록 시 helpful=true를 반환한다")
        void toggleHelpful_markAsHelpful() throws Exception {
            // given: 현재 도움이 돼요가 아닌 상태 → 등록
            when(reviewService.markHelpful(10L, USER_ID)).thenReturn(true);

            // when & then
            mockMvc.perform(post("/api/v1/reviews/{reviewId}/helpful", 10L))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.helpful").value(true));
        }

        @Test
        @DisplayName("도움이 돼요 해제 시 helpful=false를 반환한다")
        void toggleHelpful_unmarkAsHelpful() throws Exception {
            // given: 이미 도움이 돼요 상태 → 해제
            when(reviewService.markHelpful(10L, USER_ID)).thenReturn(false);

            // when & then
            mockMvc.perform(post("/api/v1/reviews/{reviewId}/helpful", 10L))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.helpful").value(false));
        }
    }
}
