package com.shop.global.exception;

import com.shop.global.dto.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ApiExceptionHandler 단위 테스트.
 *
 * <p>REST API 전용 예외 핸들러의 4가지 핸들러 메서드를 검증한다:
 * - handleNotFound: ResourceNotFoundException → 404
 * - handleBusiness: BusinessException → 400
 * - handleValidation: BindException → 400 (필드 에러 결합)
 * - handleGeneral: Exception → 500
 *
 * <p>기존 GlobalExceptionHandlerTest는 SSR 핸들러만 검증했으므로
 * REST API 핸들러 커버리지가 0%였다.</p>
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    @DisplayName("ResourceNotFoundException → 404 + 에러 코드/메시지 반환")
    void handleNotFound_returns404WithMessage() {
        // given: 존재하지 않는 상품 리소스 예외
        ResourceNotFoundException ex = new ResourceNotFoundException("상품", 999L);

        // when
        ResponseEntity<ApiResponse<Void>> response = handler.handleNotFound(ex);

        // then: HTTP 404 + ApiResponse.error 형식
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().error().message()).contains("999");
    }

    @Test
    @DisplayName("BusinessException → 400 + 에러 코드/메시지 반환")
    void handleBusiness_returns400WithCodeAndMessage() {
        // given: 재고 부족 비즈니스 예외
        BusinessException ex = new BusinessException("STOCK_ERROR", "재고가 부족합니다.");

        // when
        ResponseEntity<ApiResponse<Void>> response = handler.handleBusiness(ex);

        // then: HTTP 400 + 에러 코드와 메시지가 ApiResponse에 포함
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().error().message()).isEqualTo("재고가 부족합니다.");
    }

    @Test
    @DisplayName("BindException + 필드 에러 → 400 + '필드: 메시지' 형식 결합")
    void handleValidation_bindException_combinesFieldErrors() {
        // given: 두 개의 필드 에러를 가진 BindException
        // BindException이 FieldError를 포함할 때 "필드명: 에러메시지" 형식으로 결합되어야 한다
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "productName", "상품명을 입력해주세요"));
        bindingResult.addError(new FieldError("request", "price", "가격은 0보다 커야 합니다"));
        BindException ex = new BindException(bindingResult);

        // when
        ResponseEntity<ApiResponse<Void>> response = handler.handleValidation(ex);

        // then: 모든 필드 에러가 쉼표로 결합되어 반환
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().message()).contains("productName");
        assertThat(response.getBody().error().message()).contains("price");
    }

    @Test
    @DisplayName("BindException + 필드 에러 없음 → 기본 메시지 반환")
    void handleValidation_noFieldErrors_returnsDefaultMessage() {
        // given: 필드 에러가 없는 BindException (이론적으로 발생 가능한 엣지 케이스)
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        BindException ex = new BindException(bindingResult);

        // when
        ResponseEntity<ApiResponse<Void>> response = handler.handleValidation(ex);

        // then: 필드 에러가 없으면 기본 메시지 반환
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().message()).isEqualTo("입력값이 올바르지 않습니다.");
    }

    @Test
    @DisplayName("일반 Exception이 BindException이 아닌 경우 → 기본 메시지 반환")
    void handleValidation_nonBindException_returnsDefaultMessage() {
        // given: BindException이 아닌 일반 Exception이 핸들러에 전달되는 엣지 케이스
        // handleValidation의 파라미터 타입이 Exception이므로 instanceof BindException이 false인 분기 검증
        Exception ex = new Exception("일반 예외");

        // when
        ResponseEntity<ApiResponse<Void>> response = handler.handleValidation(ex);

        // then: instanceof BindException 분기를 타지 않으므로 기본 메시지
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().message()).isEqualTo("입력값이 올바르지 않습니다.");
    }

    @Test
    @DisplayName("예상치 못한 Exception → 500 + 서버 오류 메시지")
    void handleGeneral_returns500WithGenericMessage() {
        // given: 예상치 못한 런타임 예외
        Exception ex = new RuntimeException("NullPointerException 발생");

        // when
        ResponseEntity<ApiResponse<Void>> response = handler.handleGeneral(ex);

        // then: 내부 에러 메시지가 노출되지 않고 일반 메시지 반환 (보안)
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().message()).isEqualTo("서버 오류가 발생했습니다.");
    }
}
