package com.shop.domain.review.controller;

import com.shop.domain.review.dto.ReviewCreateRequest;
import com.shop.domain.review.service.ReviewService;
import com.shop.global.exception.BusinessException;
import com.shop.global.security.SecurityUtil;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping
    public String createReview(@Valid ReviewCreateRequest request,
                               BindingResult bindingResult,
                               RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            if (request.productId() == null) {
                redirectAttributes.addFlashAttribute("errorMessage", "리뷰 등록에 실패했습니다. 상품 정보가 누락되었습니다.");
                redirectAttributes.addFlashAttribute("org.springframework.validation.BindingResult.reviewCreateRequest", bindingResult);
                redirectAttributes.addFlashAttribute("reviewCreateRequest", request);
                return "redirect:/products";
            }

            redirectAttributes.addFlashAttribute("errorMessage",
                    "리뷰 등록에 실패했습니다. 입력값을 확인해주세요. (제목 200자/내용 5,000자 이내, 공백만 입력 불가)");
            redirectAttributes.addFlashAttribute("org.springframework.validation.BindingResult.reviewCreateRequest", bindingResult);
            redirectAttributes.addFlashAttribute("reviewCreateRequest", request);
            return "redirect:/products/" + request.productId();
        }

        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        try {
            reviewService.createReview(userId, request);
            redirectAttributes.addFlashAttribute("successMessage", "리뷰가 등록되었습니다.");
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/products/" + request.productId();
    }

    @PostMapping("/{reviewId}/helpful")
    public String markHelpful(@PathVariable Long reviewId, @RequestParam Long productId,
                              RedirectAttributes redirectAttributes) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        try {
            boolean helpfulOn = reviewService.markHelpful(reviewId, userId);
            redirectAttributes.addFlashAttribute("successMessage",
                    helpfulOn ? "도움이 됐어요를 눌렀습니다." : "도움이 됐어요를 취소했습니다.");
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/products/" + productId;
    }

    @PostMapping("/{reviewId}/delete")
    public String deleteReview(@PathVariable Long reviewId, @RequestParam Long productId,
                               RedirectAttributes redirectAttributes) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        try {
            reviewService.deleteReview(reviewId, userId);
            redirectAttributes.addFlashAttribute("successMessage", "리뷰가 삭제되었습니다.");
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/products/" + productId;
    }
}
