package com.shop.domain.coupon.controller;

import com.shop.domain.coupon.service.CouponService;
import com.shop.global.common.PagingParams;
import com.shop.global.exception.BusinessException;
import com.shop.global.security.SecurityUtil;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/coupons")
public class CouponController {

    private final CouponService couponService;

    public CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    @GetMapping
    public String couponPage(@RequestParam(defaultValue = "0") int page, Model model) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        int normalizedPage = PagingParams.normalizePage(page);
        model.addAttribute("availableCoupons", couponService.getActiveCoupons(PageRequest.of(normalizedPage, 20)));
        model.addAttribute("myCoupons", couponService.getUserCoupons(userId, PageRequest.of(0, 20)));
        model.addAttribute("issuedCouponIds", couponService.getUserIssuedCouponIds(userId));
        return "coupon/index";
    }

    @PostMapping("/issue")
    public String issueCoupon(@RequestParam String couponCode, RedirectAttributes redirectAttributes) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        try {
            couponService.issueCoupon(userId, couponCode);
            redirectAttributes.addFlashAttribute("successMessage", "쿠폰이 발급되었습니다.");
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/coupons";
    }

    @PostMapping("/issue/{couponId}")
    public String issueCouponById(@PathVariable Integer couponId, RedirectAttributes redirectAttributes) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        try {
            couponService.issueCouponById(userId, couponId);
            redirectAttributes.addFlashAttribute("successMessage", "쿠폰이 발급되었습니다.");
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/coupons";
    }
}
