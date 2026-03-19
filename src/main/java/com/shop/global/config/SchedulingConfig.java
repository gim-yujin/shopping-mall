package com.shop.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * [Phase 19] 스케줄링 활성화를 프로퍼티로 제어하는 설정 클래스.
 *
 * <h3>기존 문제</h3>
 * <p>{@code @EnableScheduling}이 {@code ShopApplication}과 {@code CacheConfig}에 직접 선언되어,
 * 테스트 환경에서 스케줄링을 비활성화할 수 없었다.
 * 다수의 {@code @SpringBootTest} 컨텍스트가 동일 DB를 공유하는 환경에서,
 * 한 컨텍스트가 {@code test-reset.sql}({@code DROP SCHEMA CASCADE})을 실행하는 동안
 * 다른 컨텍스트의 {@code @Scheduled} 메서드(OutboxEventPoller 5초, IdempotencyCleanupScheduler 1분 등)가
 * 삭제된 테이블에 접근하여 {@code "relation does not exist"} 오류가 발생했다.
 * 이 오류가 컨텍스트 로딩 중 발생하면 해당 컨텍스트의 모든 테스트가 실패한다.</p>
 *
 * <h3>해결</h3>
 * <p>{@code app.scheduling.enabled=false}를 테스트 {@code application.yml}에 설정하면
 * 모든 {@code @Scheduled} 메서드가 비활성화된다. 테스트에서 스케줄러 동작이 필요한 경우
 * 해당 메서드를 직접 호출하여 결정적(deterministic)으로 검증한다.</p>
 *
 * <p>운영 환경에서는 프로퍼티 미설정 시 기본값 {@code true}로 스케줄링이 정상 동작한다.</p>
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
