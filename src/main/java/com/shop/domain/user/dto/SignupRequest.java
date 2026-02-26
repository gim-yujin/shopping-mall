package com.shop.domain.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
    @NotBlank(message = "아이디를 입력해주세요.")
    @Size(min = 4, max = 50, message = "아이디는 4~50자로 입력해주세요.")
    String username,

    @NotBlank(message = "이메일을 입력해주세요.")
    @Email(message = "올바른 이메일 형식이 아닙니다.")
    String email,

    @NotBlank(message = "비밀번호를 입력해주세요.")
    @Size(min = 8, max = 100, message = "비밀번호는 8자 이상 입력해주세요.")
    @Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z\\d]).{8,100}$",
        message = "비밀번호는 영문, 숫자, 특수문자를 포함해야 합니다."
    )
    String password,

    @NotBlank(message = "이름을 입력해주세요.")
    String name,

    String phone
) {}
