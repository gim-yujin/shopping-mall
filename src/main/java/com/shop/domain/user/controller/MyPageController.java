package com.shop.domain.user.controller;

import com.shop.domain.coupon.service.CouponService;
import com.shop.domain.order.service.OrderService;
import com.shop.domain.review.service.ReviewService;
import com.shop.domain.user.dto.PasswordChangeRequest;
import com.shop.domain.user.dto.ProfileUpdateRequest;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.service.UserService;
import com.shop.global.exception.BusinessException;
import com.shop.global.security.SecurityUtil;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
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
        User user = userService.findById(userId);
        populateProfilePageModel(model, user);
        return "mypage/profile";
    }

    @PostMapping("/profile")
    public String updateProfile(@Valid @ModelAttribute("profileUpdateRequest") ProfileUpdateRequest request,
                                BindingResult bindingResult,
                                Model model,
                                RedirectAttributes redirectAttributes) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        User user = userService.findById(userId);

        if (bindingResult.hasErrors()) {
            populateProfilePageModel(model, user);
            model.addAttribute("profileErrorMessage", "입력값을 확인해주세요.");
            return "mypage/profile";
        }

        try {
            userService.updateProfile(userId, request.getName(), request.getPhone(), request.getEmail());
            redirectAttributes.addFlashAttribute("successMessage", "프로필이 수정되었습니다.");
            return "redirect:/mypage/profile";
        } catch (BusinessException e) {
            populateProfilePageModel(model, user);
            model.addAttribute("profileErrorMessage", e.getMessage());
            return "mypage/profile";
        }
    }

    @PostMapping("/password")
    public String changePassword(@Valid @ModelAttribute("passwordChangeRequest") PasswordChangeRequest request,
                                 BindingResult bindingResult,
                                 Model model,
                                 RedirectAttributes redirectAttributes) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        User user = userService.findById(userId);

        if (bindingResult.hasErrors()) {
            populateProfilePageModel(model, user);
            model.addAttribute("passwordErrorMessage", "입력값을 확인해주세요.");
            return "mypage/profile";
        }

        try {
            userService.changePassword(userId, request.getCurrentPassword(), request.getNewPassword());
            redirectAttributes.addFlashAttribute("successMessage", "비밀번호가 변경되었습니다.");
            return "redirect:/mypage/profile";
        } catch (BusinessException e) {
            populateProfilePageModel(model, user);
            model.addAttribute("passwordErrorMessage", e.getMessage());
            return "mypage/profile";
        }
    }

    @GetMapping("/reviews")
    public String myReviews(@RequestParam(defaultValue = "0") int page, Model model) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        model.addAttribute("reviews", reviewService.getUserReviews(userId, PageRequest.of(page, 10)));
        return "mypage/reviews";
    }

    private void populateProfilePageModel(Model model, User user) {
        model.addAttribute("user", user);
        if (!model.containsAttribute("profileUpdateRequest")) {
            model.addAttribute("profileUpdateRequest",
                    new ProfileUpdateRequest(user.getName(), user.getEmail(), user.getPhone()));
        }
        if (!model.containsAttribute("passwordChangeRequest")) {
            model.addAttribute("passwordChangeRequest", new PasswordChangeRequest());
        }
    }
}
