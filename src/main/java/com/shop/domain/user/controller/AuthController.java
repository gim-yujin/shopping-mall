package com.shop.domain.user.controller;

import com.shop.domain.user.dto.SignupRequest;
import com.shop.domain.user.service.UserService;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.DuplicateConstraintMessageResolver;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/auth")
public class AuthController {

    private final UserService userService;
    private final DuplicateConstraintMessageResolver duplicateConstraintMessageResolver;

    public AuthController(UserService userService,
                          DuplicateConstraintMessageResolver duplicateConstraintMessageResolver) {
        this.userService = userService;
        this.duplicateConstraintMessageResolver = duplicateConstraintMessageResolver;
    }

    @GetMapping("/login")
    public String loginPage() {
        return "auth/login";
    }

    @GetMapping("/signup")
    public String signupPage(Model model) {
        model.addAttribute("signupRequest", new SignupRequest("", "", "", "", ""));
        return "auth/signup";
    }

    @PostMapping("/signup")
    public String signup(@Valid @ModelAttribute SignupRequest signupRequest,
                         BindingResult bindingResult,
                         RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            return "auth/signup";
        }
        try {
            userService.signup(signupRequest);
            redirectAttributes.addFlashAttribute("successMessage", "회원가입이 완료되었습니다. 로그인해주세요.");
            return "redirect:/auth/login";
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/auth/signup";
        } catch (DataIntegrityViolationException e) {
            redirectAttributes.addFlashAttribute("errorMessage", duplicateConstraintMessageResolver.resolve(e));
            return "redirect:/auth/signup";
        }
    }
}
