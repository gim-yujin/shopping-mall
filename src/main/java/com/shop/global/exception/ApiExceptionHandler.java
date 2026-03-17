package com.shop.global.exception;

import com.shop.global.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.stream.Collectors;

/**
 * [P1-6] REST API 전용 예외 핸들러.
 *
 * 기존 GlobalExceptionHandler는 SSR(Thymeleaf) 응답을 위해 HTML 리다이렉트와
 * 에러 페이지를 반환한다. REST API 컨트롤러(@RestController)에서 발생한 예외는
 * JSON 형식의 표준 에러 응답({@link ApiResponse})으로 변환해야 하므로 별도 핸들러가 필요하다.
 *
 * 적용 대상: @RestController 어노테이션이 선언된 컨트롤러만.
 * 기존 @Controller(SSR)에는 영향을 주지 않는다.
 *
 * 우선순위: GlobalExceptionHandler보다 높게 설정하여 @RestController 예외를
 * 이 핸들러가 먼저 처리하도록 한다.
 */
@RestControllerAdvice(annotations = RestController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException e) {
        log.warn("API Resource not found: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        log.warn("API Business error [{}]: {}", e.getCode(), e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    /**
     * [Phase 3 코드 품질] 모든 필드 에러를 응답에 포함하도록 개선.
     *
     * <p><b>문제:</b> 기존에는 {@code getFieldError()}로 첫 번째 에러만 추출하여
     * 클라이언트에 반환했다. 폼에 여러 필드 오류가 있을 때 사용자가 한 번에 하나씩만
     * 에러를 확인/수정해야 했고, API 클라이언트는 전체 유효성 검증 결과를 받을 수 없었다.</p>
     *
     * <p><b>해결:</b> {@code getFieldErrors()}로 모든 필드 에러를 수집하여
     * "필드명: 에러메시지" 형식으로 결합하여 반환한다. API 클라이언트는 한 번의 요청으로
     * 모든 검증 실패 항목을 확인하고 일괄 수정할 수 있다.</p>
     */
    @ExceptionHandler({BindException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ApiResponse<Void>> handleValidation(Exception e) {
        String message = "입력값이 올바르지 않습니다.";
        if (e instanceof BindException bindException) {
            List<FieldError> fieldErrors = bindException.getBindingResult().getFieldErrors();
            if (!fieldErrors.isEmpty()) {
                message = fieldErrors.stream()
                        .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                        .collect(Collectors.joining(", "));
            }
        }
        log.warn("API Validation error: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception e) {
        log.error("API Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("INTERNAL_ERROR", "서버 오류가 발생했습니다."));
    }
}
