package com.shop.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc-openapi 메타데이터 설정.
 *
 * <p>전역 SecurityScheme으로 세션 쿠키(JSESSIONID) 기반 인증을 선언한다.
 * Swagger UI에서 'Try it out' 사용 전, 같은 브라우저에서 {@code /auth/login} 폼 로그인을 통해
 * JSESSIONID 쿠키를 선발급받아야 한다.</p>
 *
 * <p>컨트롤러/DTO에 별도 어노테이션은 추가하지 않으며, springdoc의 자동 추론에 위임한다.</p>
 */
@Configuration
public class OpenApiConfig {

    private static final String COOKIE_AUTH_SCHEME = "cookieAuth";

    @Bean
    public OpenAPI shoppingMallOpenAPI() {
        Info info = new Info()
                .title("Shopping Mall API")
                .version("v1")
                .description("Spring Boot 기반 e-commerce REST API 명세.\n\n"
                        + "**인증**: 보호된 엔드포인트는 세션 쿠키(JSESSIONID) 기반으로 인증한다. "
                        + "Swagger UI에서 'Try it out'을 사용하려면 먼저 같은 브라우저에서 "
                        + "`/auth/login` 으로 폼 로그인하여 JSESSIONID 쿠키를 발급받아야 한다.\n\n"
                        + "**공개 엔드포인트**: 상품 조회(`/api/v1/products`), 검색(`/api/v1/search`), "
                        + "회원가입(`/api/v1/users/signup`), 플래시 세일 GET(`/api/v1/flash-sales`).");

        SecurityScheme cookieScheme = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.COOKIE)
                .name("JSESSIONID")
                .description("Spring Security 폼 로그인 후 발급되는 세션 쿠키");

        return new OpenAPI()
                .info(info)
                .components(new Components().addSecuritySchemes(COOKIE_AUTH_SCHEME, cookieScheme))
                .addSecurityItem(new SecurityRequirement().addList(COOKIE_AUTH_SCHEME));
    }
}
