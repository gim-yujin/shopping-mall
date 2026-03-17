package com.shop.global.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * RateLimitService 단위 테스트.
 *
 * 사용자/IP별 토큰 버킷 관리 로직을 검증한다.
 * RateLimitFilter(HTTP 필터 계층)와 TokenBucket(알고리즘)에는 이미 테스트가 있으나,
 * 이 서비스 계층(버킷 생성·조회·키 분리)은 직접 테스트가 없었다.
 *
 * Spring 컨텍스트 불필요 — Caffeine 캐시를 내부적으로 직접 생성하므로
 * 순수 Java 테스트로 실행 가능하다.
 */
class RateLimitServiceTest {

    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        rateLimitService = new RateLimitService();
    }

    @Test
    @DisplayName("인증 사용자 — 첫 요청은 허용되고 잔여 토큰이 감소한다")
    void tryConsume_authenticatedUser_firstRequestAllowed() {
        // given: ORDER 플랜 (용량 5)
        Long userId = 1L;

        // when
        TokenBucket.ConsumeResult result = rateLimitService.tryConsume(userId, RateLimitPlan.ORDER);

        // then: 첫 요청은 항상 허용, 잔여 토큰 = 용량 - 1 = 4
        assertThat(result.allowed()).isTrue();
        assertThat(result.remainingTokens()).isEqualTo(RateLimitPlan.ORDER.getCapacity() - 1);
    }

    @Test
    @DisplayName("인증 사용자 — 토큰 소진 시 요청 거부")
    void tryConsume_authenticatedUser_exhausted_denied() {
        // given: ORDER 플랜(용량 5)의 토큰을 모두 소진
        Long userId = 1L;
        for (int i = 0; i < RateLimitPlan.ORDER.getCapacity(); i++) {
            rateLimitService.tryConsume(userId, RateLimitPlan.ORDER);
        }

        // when: 6번째 요청
        TokenBucket.ConsumeResult result = rateLimitService.tryConsume(userId, RateLimitPlan.ORDER);

        // then: 토큰 소진으로 거부, retryAfterSec > 0
        assertThat(result.allowed()).isFalse();
        assertThat(result.remainingTokens()).isEqualTo(0);
        assertThat(result.retryAfterSec()).isGreaterThan(0);
    }

    @Test
    @DisplayName("비인증 사용자(IP 기반) — 첫 요청 허용")
    void tryConsumeAnonymous_firstRequestAllowed() {
        // given: READ 플랜 (용량 60)
        String clientIp = "192.168.1.100";

        // when
        TokenBucket.ConsumeResult result = rateLimitService.tryConsumeAnonymous(clientIp, RateLimitPlan.READ);

        // then
        assertThat(result.allowed()).isTrue();
        assertThat(result.remainingTokens()).isEqualTo(RateLimitPlan.READ.getCapacity() - 1);
    }

    @Test
    @DisplayName("다른 플랜은 독립적 — READ 소진이 ORDER에 영향 없음")
    void tryConsume_differentPlans_independent() {
        // given: 같은 사용자가 READ 플랜의 토큰을 모두 소진
        // 플랜별로 키가 분리("userId:READ" vs "userId:ORDER")되므로
        // READ 한도 소진이 ORDER에 영향을 주지 않아야 한다.
        Long userId = 2L;
        for (int i = 0; i < RateLimitPlan.READ.getCapacity(); i++) {
            rateLimitService.tryConsume(userId, RateLimitPlan.READ);
        }

        // when: ORDER 플랜 요청
        TokenBucket.ConsumeResult orderResult = rateLimitService.tryConsume(userId, RateLimitPlan.ORDER);
        // READ 플랜 추가 요청
        TokenBucket.ConsumeResult readResult = rateLimitService.tryConsume(userId, RateLimitPlan.READ);

        // then: ORDER는 허용, READ는 거부
        assertThat(orderResult.allowed()).isTrue();
        assertThat(readResult.allowed()).isFalse();
    }

    @Test
    @DisplayName("다른 사용자는 독립적 — 사용자 A 소진이 사용자 B에 영향 없음")
    void tryConsume_differentUsers_independent() {
        // given: 사용자 A가 ORDER 플랜 소진
        Long userA = 10L;
        Long userB = 20L;
        for (int i = 0; i < RateLimitPlan.ORDER.getCapacity(); i++) {
            rateLimitService.tryConsume(userA, RateLimitPlan.ORDER);
        }

        // when: 사용자 B의 요청
        TokenBucket.ConsumeResult result = rateLimitService.tryConsume(userB, RateLimitPlan.ORDER);

        // then: 사용자 B는 독립 버킷이므로 허용
        assertThat(result.allowed()).isTrue();
    }

    @Test
    @DisplayName("getLimit — 플랜별 버킷 용량을 정확히 반환한다")
    void getLimit_returnsCapacityForPlan() {
        // RateLimitFilter가 X-RateLimit-Limit 헤더에 이 값을 사용한다
        assertThat(rateLimitService.getLimit(RateLimitPlan.ORDER)).isEqualTo(5);
        assertThat(rateLimitService.getLimit(RateLimitPlan.COUPON)).isEqualTo(10);
        assertThat(rateLimitService.getLimit(RateLimitPlan.WRITE)).isEqualTo(30);
        assertThat(rateLimitService.getLimit(RateLimitPlan.READ)).isEqualTo(60);
        assertThat(rateLimitService.getLimit(RateLimitPlan.DEFAULT)).isEqualTo(30);
    }
}
