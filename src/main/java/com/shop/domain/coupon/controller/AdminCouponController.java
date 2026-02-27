package com.shop.domain.coupon.controller;

import com.shop.domain.coupon.dto.AdminCouponRequest;
import com.shop.domain.coupon.entity.Coupon;
import com.shop.domain.coupon.entity.DiscountType;
import com.shop.domain.coupon.service.CouponService;
import com.shop.global.common.PageDefaults;
import com.shop.global.common.PagingParams;
import com.shop.global.exception.BusinessException;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 관리자 쿠폰 CRUD 컨트롤러.
 *
 * 기존에는 쿠폰을 DB에 직접 INSERT해야만 생성할 수 있었다.
 * 이 컨트롤러를 통해 관리자가 웹 UI에서 쿠폰을 생성/수정/비활성화할 수 있다.
 *
 * URL 규칙: /admin/coupons/** (SecurityConfig에서 hasRole('ADMIN')으로 보호)
 */
@Controller
@RequestMapping("/admin/coupons")
public class AdminCouponController {

    private final CouponService couponService;

    public AdminCouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    // ──────────── 목록 ────────────

    @GetMapping
    public String couponList(@RequestParam(defaultValue = "0") int page, Model model) {
        int normalizedPage = PagingParams.normalizePage(page);
        model.addAttribute("coupons",
                couponService.getAllCouponsForAdmin(PageRequest.of(normalizedPage, PageDefaults.ADMIN_LIST_SIZE)));
        return "admin/coupons";
    }

    // ──────────── 등록 ────────────

    @GetMapping("/new")
    public String newCouponForm(Model model) {
        model.addAttribute("request", new AdminCouponRequest());
        model.addAttribute("discountTypes", DiscountType.values());
        model.addAttribute("editMode", false);
        return "admin/coupon-form";
    }

    @PostMapping
    public String createCoupon(@Valid @ModelAttribute("request") AdminCouponRequest request,
                               BindingResult bindingResult,
                               Model model,
                               RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("discountTypes", DiscountType.values());
            model.addAttribute("editMode", false);
            return "admin/coupon-form";
        }

        try {
            Coupon coupon = couponService.createCoupon(request);
            redirectAttributes.addFlashAttribute("successMessage",
                    "쿠폰 '" + coupon.getCouponName() + "'이(가) 생성되었습니다.");
            return "redirect:/admin/coupons";
        } catch (BusinessException e) {
            model.addAttribute("errorMessage", e.getMessage());
            model.addAttribute("discountTypes", DiscountType.values());
            model.addAttribute("editMode", false);
            return "admin/coupon-form";
        }
    }

    // ──────────── 수정 ────────────

    @GetMapping("/{couponId}/edit")
    public String editCouponForm(@PathVariable Integer couponId, Model model) {
        Coupon coupon = couponService.findByIdForAdmin(couponId);

        AdminCouponRequest request = new AdminCouponRequest();
        request.setCouponCode(coupon.getCouponCode());
        request.setCouponName(coupon.getCouponName());
        request.setDiscountType(coupon.getDiscountType());
        request.setDiscountValue(coupon.getDiscountValue());
        request.setMinOrderAmount(coupon.getMinOrderAmount());
        request.setMaxDiscount(coupon.getMaxDiscount());
        request.setTotalQuantity(coupon.getTotalQuantity());
        request.setValidFrom(coupon.getValidFrom());
        request.setValidUntil(coupon.getValidUntil());

        model.addAttribute("request", request);
        model.addAttribute("couponId", couponId);
        model.addAttribute("coupon", coupon);
        model.addAttribute("discountTypes", DiscountType.values());
        model.addAttribute("editMode", true);
        return "admin/coupon-form";
    }

    @PostMapping("/{couponId}")
    public String updateCoupon(@PathVariable Integer couponId,
                               @Valid @ModelAttribute("request") AdminCouponRequest request,
                               BindingResult bindingResult,
                               Model model,
                               RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("couponId", couponId);
            model.addAttribute("coupon", couponService.findByIdForAdmin(couponId));
            model.addAttribute("discountTypes", DiscountType.values());
            model.addAttribute("editMode", true);
            return "admin/coupon-form";
        }

        try {
            Coupon coupon = couponService.updateCoupon(couponId, request);
            redirectAttributes.addFlashAttribute("successMessage",
                    "쿠폰 '" + coupon.getCouponName() + "'이(가) 수정되었습니다.");
            return "redirect:/admin/coupons";
        } catch (BusinessException e) {
            model.addAttribute("errorMessage", e.getMessage());
            model.addAttribute("couponId", couponId);
            model.addAttribute("coupon", couponService.findByIdForAdmin(couponId));
            model.addAttribute("discountTypes", DiscountType.values());
            model.addAttribute("editMode", true);
            return "admin/coupon-form";
        }
    }

    // ──────────── 활성/비활성 토글 ────────────

    @PostMapping("/{couponId}/toggle-active")
    public String toggleActive(@PathVariable Integer couponId, RedirectAttributes redirectAttributes) {
        couponService.toggleCouponActive(couponId);
        redirectAttributes.addFlashAttribute("successMessage", "쿠폰 상태가 변경되었습니다.");
        return "redirect:/admin/coupons";
    }
}
