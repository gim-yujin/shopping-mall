package com.shop.global.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.util.AntPathMatcher;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private static final Map<String, List<String>> REDIRECT_PATH_POLICY = new LinkedHashMap<>();

    static {
        REDIRECT_PATH_POLICY.put("/cart", List.of());
        REDIRECT_PATH_POLICY.put("/orders", List.of("page"));
        REDIRECT_PATH_POLICY.put("/orders/**", List.of("page"));
        REDIRECT_PATH_POLICY.put("/mypage", List.of());
        REDIRECT_PATH_POLICY.put("/mypage/reviews", List.of("page"));
        REDIRECT_PATH_POLICY.put("/mypage/**", List.of());
        REDIRECT_PATH_POLICY.put("/admin/orders", List.of("page", "status"));
        REDIRECT_PATH_POLICY.put("/admin/products", List.of("page"));
        REDIRECT_PATH_POLICY.put("/admin/**", List.of());
        REDIRECT_PATH_POLICY.put("/products/**", List.of("keyword", "categoryId", "sort", "page", "size"));
    }

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
     * Referer 헤더에서 안전한 내부 경로만 추출한다.
     * 허용되지 않은 경로이거나 파싱에 실패하면 "/" 로 폴백한다.
     */
    private String resolveRedirectUrl(HttpServletRequest request) {
        String referer = request.getHeader("Referer");
        if (referer == null || referer.isBlank()) {
            return "/";
        }

        try {
            URI refererUri = URI.create(referer);
            String path = Optional.ofNullable(refererUri.getPath()).orElse("");

            if (!isAllowedRedirectPath(path) || !isTrustedRefererHost(request, refererUri)) {
                return "/";
            }

            return appendAllowedQueryParams(path, refererUri);
        } catch (IllegalArgumentException e) {
            log.debug("Invalid Referer header: {}", referer);
            return "/";
        }
    }

    private boolean isAllowedRedirectPath(String path) {
        return REDIRECT_PATH_POLICY.keySet().stream()
                .anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }

    private String appendAllowedQueryParams(String path, URI refererUri) {
        List<String> allowedQueryParams = resolveAllowedQueryParams(path);
        if (allowedQueryParams.isEmpty() || refererUri.getRawQuery() == null) {
            return path;
        }

        var queryParams = UriComponentsBuilder.fromUri(refererUri)
                .build()
                .getQueryParams();

        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(path);
        for (String paramName : allowedQueryParams) {
            List<String> values = queryParams.get(paramName);
            if (values != null && !values.isEmpty()) {
                builder.queryParam(paramName, values.toArray());
            }
        }

        return builder.build().toUriString();
    }

    private List<String> resolveAllowedQueryParams(String path) {
        return REDIRECT_PATH_POLICY.entrySet().stream()
                .filter(entry -> PATH_MATCHER.match(entry.getKey(), path))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(List.of());
    }

    private boolean isTrustedRefererHost(HttpServletRequest request, URI refererUri) {
        String refererHost = refererUri.getHost();
        if (refererHost == null || refererHost.isBlank()) {
            return true;
        }

        String hostHeader = Optional.ofNullable(request.getHeader("X-Forwarded-Host"))
                .orElse(request.getHeader("Host"));

        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }

        String trustedHost = hostHeader.split(",")[0].trim();
        int portIndex = trustedHost.indexOf(":");
        if (portIndex >= 0) {
            trustedHost = trustedHost.substring(0, portIndex);
        }

        return refererHost.equalsIgnoreCase(trustedHost);
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
