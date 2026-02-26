package com.shop.global.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URI;
import java.util.Map;
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
    public Object handleBusiness(BusinessException e,
                                 HttpServletRequest request,
                                 RedirectAttributes redirectAttributes) {
        log.warn("Business error [{}]: {}", e.getCode(), e.getMessage());

        // AJAX 요청은 JSON 응답 반환
        if (isAjaxRequest(request)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getCode(), "message", e.getMessage()));
        }

        // SSR 요청: Referer 기반으로 원래 페이지로 리다이렉트
        redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        String redirectUrl = resolveRedirectUrl(request);
        return "redirect:" + redirectUrl;
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

    /**
     * Referer 헤더에서 같은 호스트의 경로를 추출한다.
     * Referer가 없거나 외부 도메인이면 "/" 로 폴백한다.
     */
    private String resolveRedirectUrl(HttpServletRequest request) {
        String referer = request.getHeader("Referer");
        if (referer == null || referer.isBlank()) {
            return "/";
        }
        try {
            URI refererUri = URI.create(referer);
            // 같은 호스트인지 검증 — Open Redirect 방지
            String serverHost = request.getServerName();
            if (!serverHost.equalsIgnoreCase(refererUri.getHost())) {
                return "/";
            }
            String path = refererUri.getPath();
            String query = refererUri.getQuery();
            return (query != null) ? path + "?" + query : path;
        } catch (IllegalArgumentException e) {
            log.debug("Invalid Referer header: {}", referer);
            return "/";
        }
    }

    private boolean isAjaxRequest(HttpServletRequest request) {
        return "XMLHttpRequest".equals(request.getHeader("X-Requested-With"))
                || (request.getHeader("Accept") != null
                    && request.getHeader("Accept").contains("application/json"));
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
