package com.shop.global.config;

import com.shop.global.security.CustomUserDetailsService;
import com.shop.global.security.LoginAuthenticationFailureHandler;
import com.shop.global.security.LoginAuthenticationSuccessHandler;
import com.shop.global.security.LoginBlockPreAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final CustomUserDetailsService userDetailsService;
    private final LoginAuthenticationFailureHandler loginAuthenticationFailureHandler;
    private final LoginAuthenticationSuccessHandler loginAuthenticationSuccessHandler;
    private final LoginBlockPreAuthenticationFilter loginBlockPreAuthenticationFilter;

    public SecurityConfig(CustomUserDetailsService userDetailsService,
                          LoginAuthenticationFailureHandler loginAuthenticationFailureHandler,
                          LoginAuthenticationSuccessHandler loginAuthenticationSuccessHandler,
                          LoginBlockPreAuthenticationFilter loginBlockPreAuthenticationFilter) {
        this.userDetailsService = userDetailsService;
        this.loginAuthenticationFailureHandler = loginAuthenticationFailureHandler;
        this.loginAuthenticationSuccessHandler = loginAuthenticationSuccessHandler;
        this.loginBlockPreAuthenticationFilter = loginBlockPreAuthenticationFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();
        requestHandler.setCsrfRequestAttributeName(null);

        http
            .authenticationProvider(authenticationProvider())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/products/**", "/categories/**", "/search/**",
                    "/auth/**", "/static/**", "/css/**", "/images/**", "/error/**").permitAll()
                // [P1-6] REST API 공개 경로: 상품 목록/상세, 상품별 리뷰 조회
                .requestMatchers("/api/v1/products/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()  // 로드밸런서 헬스체크용
                .requestMatchers("/actuator/**").hasRole("ADMIN") // 나머지 Actuator는 관리자만
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/auth/login")
                .loginProcessingUrl("/auth/login")
                .successHandler(loginAuthenticationSuccessHandler)
                .failureHandler(loginAuthenticationFailureHandler)
                .usernameParameter("username")
                .passwordParameter("password")
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/auth/logout")
                .logoutSuccessUrl("/")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
                .permitAll()
            )
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(requestHandler)
                // [P1-6] REST API 경로는 CSRF 보호 제외.
                // REST 클라이언트(모바일 앱, SPA 등)는 쿠키 기반 CSRF 토큰을 사용하지 않으며,
                // 향후 JWT 등 stateless 인증으로 전환 시 CSRF 자체가 불필요하다.
                // SSR 경로의 CSRF 보호는 기존과 동일하게 유지된다.
                .ignoringRequestMatchers("/api/**")
            )
            .rememberMe(remember -> remember
                .key("shopping-mall-remember-key")
                .tokenValiditySeconds(86400 * 7)
                .userDetailsService(userDetailsService)
            )
            // [P1-6] REST API 요청에 대해 로그인 페이지 리다이렉트 대신 401 JSON 응답 반환.
            // Accept: application/json 헤더가 포함된 요청은 API 클라이언트로 간주한다.
            .exceptionHandling(ex -> ex
                .defaultAuthenticationEntryPointFor(
                        (request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write(
                                    "{\"success\":false,\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"인증이 필요합니다.\"}}");
                        },
                        request -> request.getRequestURI().startsWith("/api/")
                )
            )
            .addFilterBefore(loginBlockPreAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
