package com.shop.global.config;

import com.shop.global.security.CustomUserDetailsService;
import com.shop.global.security.LoginAuthenticationFailureHandler;
import com.shop.global.security.LoginAuthenticationSuccessHandler;
import com.shop.global.security.LoginBlockPreAuthenticationFilter;
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
            )
            .rememberMe(remember -> remember
                .key("shopping-mall-remember-key")
                .tokenValiditySeconds(86400 * 7)
                .userDetailsService(userDetailsService)
            )
            .addFilterBefore(loginBlockPreAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
