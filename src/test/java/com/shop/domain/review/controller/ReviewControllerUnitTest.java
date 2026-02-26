package com.shop.domain.review.controller;

import com.shop.domain.review.service.ReviewService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ReviewControllerUnitTest {

    @Mock
    private ReviewService reviewService;

    @Test
    @DisplayName("createReview - productId 누락 + validation error면 안전한 기본 경로로 리다이렉트한다")
    void createReview_missingProductIdWithValidationError_redirectsToSafePath() throws Exception {
        ReviewController controller = new ReviewController(reviewService);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setValidator(validator)
                .build();

        mockMvc.perform(post("/reviews")
                        .param("orderItemId", "11")
                        .param("rating", "5")
                        .param("title", " ")
                        .param("content", "리뷰 내용"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/products"))
                .andExpect(flash().attribute("errorMessage", "리뷰 등록에 실패했습니다. 상품 정보가 누락되었습니다."));

        verifyNoInteractions(reviewService);
        validator.destroy();
    }
}
