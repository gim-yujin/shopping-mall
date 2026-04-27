package com.shop.global.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis v2 인프라 설정. {@code spring.profiles.active=redis} 일 때만 활성화된다.
 *
 * <p>{@code StringRedisTemplate} 과 {@code LettuceConnectionFactory} 는 Spring Boot 의
 * {@code RedisAutoConfiguration} 이 자동 등록한다. 본 설정은 그 위에 Lua 스크립트 빈만
 * 추가한다.</p>
 */
@Configuration
@Profile("redis")
public class RedisConfig {

    /**
     * 재고 원자 차감 Lua 스크립트.
     * 위치: {@code classpath:/redis/stock-decrement.lua}
     *
     * <p>반환 타입은 {@code Long} — Lua 가 내려주는 값은 모두 number → Long 매핑.</p>
     */
    @Bean
    public RedisScript<Long> stockDecrementScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/stock-decrement.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
