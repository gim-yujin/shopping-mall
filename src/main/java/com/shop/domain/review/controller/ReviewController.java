package com.shop.domain.review.controller;

import com.shop.domain.review.dto.ReviewCreateRequest;
import com.shop.domain.review.dto.ReviewUpdateRequest;
import com.shop.domain.review.entity.Review;
import com.shop.domain.review.service.ReviewService;
import com.shop.global.exception.BusinessException;
import com.shop.global.security.SecurityUtil;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
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

    /**
     * [3.7] 리뷰 수정 폼 표시.
     * 본인 리뷰만 수정 가능하며, 수정 후 상품 상세 페이지로 리다이렉트한다.
     */
    @GetMapping("/{reviewId}/edit")
    public String editReviewForm(@PathVariable Long reviewId, Model model) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        Review review = reviewService.getReviewForEdit(reviewId, userId);
        model.addAttribute("review", review);
        return "review/edit";
    }

    /**
     * [3.7] 리뷰 수정 처리.
     * 평점 변경 시 상품 평균 평점을 재계산하고 관련 캐시를 무효화한다.
     */
    @PostMapping("/{reviewId}/edit")
    public String updateReview(@PathVariable Long reviewId,
                               @Valid ReviewUpdateRequest request,
                               BindingResult bindingResult,
                               @RequestParam Long productId,
                               RedirectAttributes redirectAttributes,
                               Model model) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();

        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "리뷰 수정에 실패했습니다. 입력값을 확인해주세요.");
            return "redirect:/reviews/" + reviewId + "/edit";
        }

        try {
            reviewService.updateReview(reviewId, userId, request);
            redirectAttributes.addFlashAttribute("successMessage", "리뷰가 수정되었습니다.");
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/products/" + productId;
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
