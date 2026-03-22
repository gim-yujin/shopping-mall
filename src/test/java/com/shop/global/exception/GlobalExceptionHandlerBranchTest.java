package com.shop.global.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GlobalExceptionHandler 분기 커버리지 보강 테스트.
 *
 * <p>기존 GlobalExceptionHandlerTest에서 다루지 않은 핸들러 분기를 검증한다:
 * - handleNotFound: ResourceNotFoundException → error/404 뷰
 * - handleValidation: BindException → 필드 에러 결합 / 기본 메시지
 * - handleGeneral: Exception → error/500 뷰
 * - resolveRedirectUrl: 잘못된 URI 파싱 실패 분기
 * - isTrustedRefererHost: Host 헤더 없는 분기
 * - appendAllowedQueryParams: 쿼리 없는 Referer 분기</p>
 */
class GlobalExceptionHandlerBranchTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // ── handleNotFound ──

    @Test
    @DisplayName("ResourceNotFoundException → error/404 뷰 + 에러 메시지 모델 속성")
    void handleNotFound_returns404View() {
        // given: 존재하지 않는 주문 예외
        ResourceNotFoundException ex = new ResourceNotFoundException("주문", 123L);
        Model model = new ConcurrentModel();

        // when
        String view = handler.handleNotFound(ex, model);

        // then: error/404 뷰가 반환되고, 에러 메시지가 모델에 설정
        assertThat(view).isEqualTo("error/404");
        assertThat(model.getAttribute("errorMessage")).asString().contains("123");
    }

    // ── handleValidation ──

    @Test
    @DisplayName("BindException + 필드 에러 → error/400 뷰 + 결합된 메시지")
    void handleValidation_withFieldErrors_combinesMessages() {
        // given: 여러 필드 에러를 가진 BindException
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "form");
        bindingResult.addError(new FieldError("form", "name", "이름을 입력해주세요"));
        bindingResult.addError(new FieldError("form", "email", "이메일 형식이 올바르지 않습니다"));
        BindException ex = new BindException(bindingResult);
        Model model = new ConcurrentModel();

        // when
        String view = handler.handleValidation(ex, model);

        // then: 모든 필드 에러 메시지가 쉼표로 결합되어 모델에 설정
        assertThat(view).isEqualTo("error/400");
        String message = (String) model.getAttribute("errorMessage");
        assertThat(message).contains("이름을 입력해주세요");
        assertThat(message).contains("이메일 형식이 올바르지 않습니다");
    }

    @Test
    @DisplayName("BindException + 필드 에러 없음 → 기본 메시지 반환")
    void handleValidation_noFieldErrors_returnsDefaultMessage() {
        // given: 필드 에러가 없는 BindException
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "form");
        BindException ex = new BindException(bindingResult);
        Model model = new ConcurrentModel();

        // when
        String view = handler.handleValidation(ex, model);

        // then: extractValidationMessage()이 Optional.empty() 반환 → 기본 메시지
        assertThat(view).isEqualTo("error/400");
        assertThat(model.getAttribute("errorMessage"))
                .isEqualTo("입력값이 올바르지 않습니다. 입력 내용을 다시 확인해주세요.");
    }

    @Test
    @DisplayName("BindException이 아닌 Exception → 기본 메시지 반환")
    void handleValidation_nonBindException_returnsDefaultMessage() {
        // given: BindException이 아닌 일반 Exception
        // handleValidation 파라미터가 Exception이므로 instanceof BindException false 분기
        Exception ex = new Exception("일반 예외");
        Model model = new ConcurrentModel();

        // when
        String view = handler.handleValidation(ex, model);

        // then: instanceof 분기를 타지 않으므로 기본 메시지
        assertThat(view).isEqualTo("error/400");
        assertThat(model.getAttribute("errorMessage"))
                .isEqualTo("입력값이 올바르지 않습니다. 입력 내용을 다시 확인해주세요.");
    }

    // ── handleGeneral ──

    @Test
    @DisplayName("예상치 못한 Exception → error/500 뷰 + 일반 에러 메시지")
    void handleGeneral_returns500View() {
        // given: 예상치 못한 런타임 예외
        Exception ex = new RuntimeException("DB 연결 실패");
        Model model = new ConcurrentModel();

        // when
        String view = handler.handleGeneral(ex, model);

        // then: 내부 예외 정보가 노출되지 않고 일반 메시지만 반환 (보안)
        assertThat(view).isEqualTo("error/500");
        assertThat(model.getAttribute("errorMessage")).isEqualTo("서버 오류가 발생했습니다.");
    }

    // ── resolveRedirectUrl — 잘못된 URI 분기 ──

    @Test
    @DisplayName("Referer가 빈 문자열이면 홈으로 폴백")
    void handleBusiness_blankReferer_fallsBackToHome() {
        // given: 빈 문자열 Referer — resolveRedirectUrl의 isBlank() 분기
        BusinessException ex = new BusinessException("ERR", "에러");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Referer", "   ");

        // when
        Object result = handler.handleBusiness(ex, request,
                new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap());

        // then: 빈 Referer → "/" 폴백
        assertThat(result).isEqualTo("redirect:/");
    }

    @Test
    @DisplayName("Referer가 잘못된 URI 형식이면 홈으로 폴백")
    void handleBusiness_malformedReferer_fallsBackToHome() {
        // given: URI 파싱 실패하는 Referer — catch(IllegalArgumentException) 분기
        BusinessException ex = new BusinessException("ERR", "에러");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Referer", "://invalid uri{");

        // when
        Object result = handler.handleBusiness(ex, request,
                new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap());

        // then: URI 파싱 실패 → "/" 폴백
        assertThat(result).isEqualTo("redirect:/");
    }

    @Test
    @DisplayName("Host 헤더가 없으면 외부 Referer로 간주하여 홈으로 폴백")
    void handleBusiness_noHostHeader_fallsBackToHome() {
        // given: Host와 X-Forwarded-Host 모두 없는 요청
        // isTrustedRefererHost에서 hostHeader == null 분기
        BusinessException ex = new BusinessException("ERR", "에러");
        MockHttpServletRequest request = new MockHttpServletRequest();
        // Host 헤더를 설정하지 않음 — MockHttpServletRequest는 기본적으로 "localhost"를 반환하므로
        // 서버 이름과 다른 호스트의 Referer를 사용하여 차단 테스트
        request.addHeader("Referer", "http://other-host/orders");

        // when
        Object result = handler.handleBusiness(ex, request,
                new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap());

        // then: 호스트 불일치 → "/" 폴백
        assertThat(result).isEqualTo("redirect:/");
    }

    @Test
    @DisplayName("허용 경로이지만 쿼리 파라미터가 없는 Referer → 경로만 반환")
    void handleBusiness_allowedPathWithoutQuery_returnsPathOnly() {
        // given: 쿼리 파라미터가 없는 허용 경로 Referer
        // appendAllowedQueryParams에서 refererUri.getRawQuery() == null 분기
        BusinessException ex = new BusinessException("ERR", "에러");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("localhost");
        request.addHeader("Host", "localhost");
        request.addHeader("Referer", "http://localhost/cart");

        // when
        Object result = handler.handleBusiness(ex, request,
                new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap());

        // then: 쿼리 없이 경로만 반환
        assertThat(result).isEqualTo("redirect:/cart");
    }

    @Test
    @DisplayName("Referer 호스트가 null(상대 경로)이면 신뢰로 간주")
    void handleBusiness_refererWithoutHost_trustedAsRelative() {
        // given: 호스트 없는 Referer (상대 경로 형태)
        // isTrustedRefererHost에서 refererHost == null 분기 → true 반환
        BusinessException ex = new BusinessException("ERR", "에러");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("localhost");
        request.addHeader("Host", "localhost");
        request.addHeader("Referer", "/orders?page=1");

        // when
        Object result = handler.handleBusiness(ex, request,
                new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap());

        // then: 호스트가 없으면 내부 요청으로 간주하여 경로 반환
        assertThat(result).isEqualTo("redirect:/orders?page=1");
    }
}
