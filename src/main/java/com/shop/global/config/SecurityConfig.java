package com.shop.global.config;

import com.shop.global.ratelimit.RateLimitFilter;
import com.shop.global.security.CustomUserDetailsService;
import com.shop.global.security.LoginAuthenticationFailureHandler;
import com.shop.global.security.LoginAuthenticationSuccessHandler;
import com.shop.global.security.LoginBlockPreAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final AntPathRequestMatcher API_REQUEST_MATCHER =
            new AntPathRequestMatcher("/api/**");

    private final CustomUserDetailsService userDetailsService;
    private final LoginAuthenticationFailureHandler loginAuthenticationFailureHandler;
    private final LoginAuthenticationSuccessHandler loginAuthenticationSuccessHandler;
    private final LoginBlockPreAuthenticationFilter loginBlockPreAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;

    public SecurityConfig(CustomUserDetailsService userDetailsService,
                          LoginAuthenticationFailureHandler loginAuthenticationFailureHandler,
                          LoginAuthenticationSuccessHandler loginAuthenticationSuccessHandler,
                          LoginBlockPreAuthenticationFilter loginBlockPreAuthenticationFilter,
                          RateLimitFilter rateLimitFilter) {
        this.userDetailsService = userDetailsService;
        this.loginAuthenticationFailureHandler = loginAuthenticationFailureHandler;
        this.loginAuthenticationSuccessHandler = loginAuthenticationSuccessHandler;
        this.loginBlockPreAuthenticationFilter = loginBlockPreAuthenticationFilter;
        this.rateLimitFilter = rateLimitFilter;
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
    @Order(1)
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/**")
            .authenticationProvider(authenticationProvider())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/products", "/api/v1/products/**").permitAll()
                .requestMatchers("/api/v1/search/**").permitAll()
                .requestMatchers("/api/v1/users/signup").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            // API는 세션 쿠키 기반 브라우저 폼 제출이 아니므로 CSRF 비활성화
            .csrf(AbstractHttpConfigurer::disable)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(
                        "{\"success\":false,\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"인증이 필요합니다.\"}}"
                    );
                })
            )
            // API 요청에서 로그인 페이지 리다이렉트를 유발하는 RequestCache 제거
            .requestCache(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .rememberMe(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            // [P0] Rate Limit 필터: AuthorizationFilter 이후에 실행하여
            // SecurityContext에 인증 정보가 설정된 상태에서 userId를 추출한다.
            .addFilterAfter(rateLimitFilter, AuthorizationFilter.class);

        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain webFilterChain(HttpSecurity http) throws Exception {
        CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();
        requestHandler.setCsrfRequestAttributeName(null);

        http
            .authenticationProvider(authenticationProvider())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/products/**", "/categories/**", "/search/**",
                    "/auth/**", "/static/**", "/css/**", "/images/**", "/error/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()      // 로드밸런서 헬스체크용
                .requestMatchers("/actuator/prometheus").permitAll()  // Prometheus 스크래핑 허용
                .requestMatchers("/actuator/**").hasRole("ADMIN")     // 나머지 Actuator는 관리자만
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
            )
            .rememberMe(remember -> remember
                .key("shopping-mall-remember-key")
                .tokenValiditySeconds(86400 * 7)
                .userDetailsService(userDetailsService)
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/auth/login"))
            )
            .addFilterBefore(loginBlockPreAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            // [P0] Rate Limit 필터: 인증 처리 이후에 실행하여 userId 기반 속도 제한 적용
            .addFilterAfter(rateLimitFilter, AuthorizationFilter.class);

        return http.build();
    }

    /**
     * [P0] RateLimitFilter의 서블릿 자동 등록을 비활성화한다.
     *
     * <p>Spring Boot는 @Component로 등록된 Filter를 서블릿 필터로 자동 등록한다.
     * 동시에 SecurityConfig에서 addFilterAfter로도 등록하면 필터가 두 번 실행된다.
     * FilterRegistrationBean.setEnabled(false)로 서블릿 자동 등록을 방지하고,
     * Spring Security 필터 체인을 통해서만 실행되도록 한다.</p>
     */
    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<RateLimitFilter>
            rateLimitFilterRegistration(RateLimitFilter filter) {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
