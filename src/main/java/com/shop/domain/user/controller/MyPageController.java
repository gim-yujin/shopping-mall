package com.shop.domain.user.controller;

import com.shop.domain.coupon.service.CouponService;
import com.shop.domain.order.service.OrderService;
import com.shop.domain.review.service.ReviewService;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.service.UserService;
import com.shop.global.exception.BusinessException;
import com.shop.global.security.SecurityUtil;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/mypage")
public class MyPageController {

    private final UserService userService;
    private final OrderService orderService;
    private final ReviewService reviewService;
    private final CouponService couponService;

    public MyPageController(UserService userService, OrderService orderService,
                            ReviewService reviewService, CouponService couponService) {
        this.userService = userService;
        this.orderService = orderService;
        this.reviewService = reviewService;
        this.couponService = couponService;
    }

    @GetMapping
    public String myPage(Model model) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        User user = userService.findById(userId);
        model.addAttribute("user", user);
        model.addAttribute("recentOrders", orderService.getOrdersByUser(userId, PageRequest.of(0, 5)));
        model.addAttribute("coupons", couponService.getAvailableCoupons(userId));
        return "mypage/index";
    }

    @GetMapping("/profile")
    public String profilePage(Model model) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        model.addAttribute("user", userService.findById(userId));
        return "mypage/profile";
    }

    @PostMapping("/profile")
    public String updateProfile(@RequestParam String name, @RequestParam String phone,
                                @RequestParam String email, RedirectAttributes redirectAttributes) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        try {
            userService.updateProfile(userId, name, phone, email);
            redirectAttributes.addFlashAttribute("successMessage", "프로필이 수정되었습니다.");
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/mypage/profile";
    }

    @PostMapping("/password")
    public String changePassword(@RequestParam String currentPassword, @RequestParam String newPassword,
                                 RedirectAttributes redirectAttributes) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        try {
            userService.changePassword(userId, currentPassword, newPassword);
            redirectAttributes.addFlashAttribute("successMessage", "비밀번호가 변경되었습니다.");
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/mypage/profile";
    }

    @GetMapping("/reviews")
    public String myReviews(@RequestParam(defaultValue = "0") int page, Model model) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        model.addAttribute("reviews", reviewService.getUserReviews(userId, PageRequest.of(page, 10)));
        return "mypage/reviews";
    }
}
