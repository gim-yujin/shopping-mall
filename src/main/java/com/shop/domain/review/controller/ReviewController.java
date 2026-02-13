package com.shop.domain.review.controller;

import com.shop.domain.review.dto.ReviewCreateRequest;
import com.shop.domain.review.service.ReviewService;
import com.shop.global.exception.BusinessException;
import com.shop.global.security.SecurityUtil;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
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
    public String createReview(@Valid ReviewCreateRequest request, RedirectAttributes redirectAttributes) {
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
            boolean added = reviewService.markHelpful(reviewId, userId);
            redirectAttributes.addFlashAttribute("successMessage",
                    added ? "도움이 됐어요를 눌렀습니다." : "도움이 됐어요를 취소했습니다.");
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
