package com.shop.global.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Optional;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(ResourceNotFoundException e, Model model) {
        log.warn("Resource not found: {}", e.getMessage());
        model.addAttribute("errorMessage", e.getMessage());
        return "error/404";
    }

    @ExceptionHandler(BusinessException.class)
    public String handleBusiness(BusinessException e, RedirectAttributes redirectAttributes) {
        log.warn("Business error [{}]: {}", e.getCode(), e.getMessage());
        redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        return "redirect:/";
    }

    /**
     * DB UNIQUE 제약 위반 처리.
     * 동시 요청으로 인해 서비스 레이어의 existsBy() 검증을 통과한 후
     * INSERT/UPDATE 시 UNIQUE 위반이 발생하는 경우를 처리한다.
     * 500 대신 사용자 친화적인 409 Conflict 응답을 반환한다.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public String handleDataIntegrity(DataIntegrityViolationException e,
                                      RedirectAttributes redirectAttributes) {
        String message = e.getMessage();
        String userMessage;

        if (message != null && message.contains("users_username_key")) {
            userMessage = "이미 사용 중인 아이디입니다.";
        } else if (message != null && message.contains("users_email_key")) {
            userMessage = "이미 사용 중인 이메일입니다.";
        } else {
            log.error("Unhandled data integrity violation", e);
            userMessage = "중복된 데이터가 존재합니다. 다시 시도해주세요.";
        }

        log.warn("Data integrity violation (concurrent duplicate): {}", userMessage);
        redirectAttributes.addFlashAttribute("errorMessage", userMessage);
        return "redirect:/";
    }

    @ExceptionHandler({BindException.class, MethodArgumentNotValidException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleValidation(Exception e, Model model) {
        String message = extractValidationMessage(e)
                .orElse("입력값이 올바르지 않습니다. 입력 내용을 다시 확인해주세요.");
        log.warn("Validation error: {}", message);
        model.addAttribute("errorMessage", message);
        return "error/400";
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String handleGeneral(Exception e, Model model) {
        log.error("Unexpected error", e);
        model.addAttribute("errorMessage", "서버 오류가 발생했습니다.");
        return "error/500";
    }

    private Optional<String> extractValidationMessage(Exception e) {
        if (e instanceof BindException bindException) {
            return Optional.ofNullable(bindException.getBindingResult().getFieldError())
                    .map(FieldError::getDefaultMessage);
        }

        if (e instanceof MethodArgumentNotValidException methodArgumentNotValidException) {
            return Optional.ofNullable(methodArgumentNotValidException.getBindingResult().getFieldError())
                    .map(FieldError::getDefaultMessage);
        }

        return Optional.empty();
    }
}
