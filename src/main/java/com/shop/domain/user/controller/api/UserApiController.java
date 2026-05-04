package com.shop.domain.user.controller.api;

import com.shop.domain.user.dto.PasswordChangeRequest;
import com.shop.domain.user.dto.ProfileUpdateRequest;
import com.shop.domain.user.dto.SignupRequest;
import com.shop.domain.user.dto.UserProfileResponse;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.service.UserService;
import com.shop.global.dto.ApiResponse;
import com.shop.global.security.SecurityUtil;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 사용자 REST API 컨트롤러.
 *
 * <p>기존 AuthController(SSR)의 회원가입과 MyPageController(SSR)의 프로필 관리를
 * JSON 기반 REST API로 제공한다.</p>
 *
 * <ul>
 *   <li>회원가입(POST /signup) — 공개 API, SecurityConfig에서 permitAll</li>
 *   <li>그 외 /me/** — 인증 필요, 본인 정보만 접근 가능</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserApiController {

    private final UserService userService;

    public UserApiController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 회원가입 (공개).
     *
     * <p>기존 SSR의 POST /auth/signup과 동일한 SignupRequest를 사용한다.
     * username, email, password 형식 검증과 중복 확인을 수행한다.</p>
     */
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserProfileResponse> signup(@Valid @RequestBody SignupRequest request) {
        User user = userService.signup(request);
        return ApiResponse.ok(UserProfileResponse.from(user));
    }

    /**
     * 내 프로필 조회.
     */
    @GetMapping("/me")
    public ApiResponse<UserProfileResponse> getMyProfile() {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        User user = userService.findById(userId);
        return ApiResponse.ok(UserProfileResponse.from(user));
    }

    /**
     * 프로필 수정.
     *
     * <p>이름, 이메일, 전화번호를 변경한다.
     * 이메일 중복 확인 및 전화번호 형식 검증을 수행한다.</p>
     */
    @PutMapping("/me/profile")
    public ApiResponse<Void> updateProfile(@Valid @RequestBody ProfileUpdateRequest request) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        userService.updateProfile(userId, request.getName(), request.getPhone(), request.getEmail());
        return ApiResponse.ok();
    }

    /**
     * 비밀번호 변경.
     *
     * <p>현재 비밀번호 확인 후 새 비밀번호로 변경한다.
     * 새 비밀번호는 영문+숫자+특수문자 조합 8자 이상이어야 한다.</p>
     */
    @PostMapping("/me/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody PasswordChangeRequest request) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        userService.changePassword(userId, request.getCurrentPassword(), request.getNewPassword());
        return ApiResponse.ok();
    }
}
