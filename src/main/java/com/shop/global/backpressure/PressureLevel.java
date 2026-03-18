package com.shop.global.backpressure;

/**
 * [Phase 12] 시스템 부하 수준을 나타내는 열거형.
 *
 * <h3>문제: 과부하 시 비필수 작업이 시스템을 더 악화시킨다</h3>
 * <p>비동기 Executor 큐가 포화되면 조회수 증가, 검색 로그 저장 같은 비필수 작업이
 * 큐에 계속 쌓여 메모리를 소비하고, 큐 오버플로 시 RejectedExecutionException이
 * 호출 스레드(HTTP 요청 스레드)로 전파되어 정상 응답까지 실패하게 만든다.</p>
 *
 * <h3>해결: 부하 수준별 단계적 저하(Graceful Degradation)</h3>
 * <ul>
 *   <li>{@link #NORMAL}: 모든 작업 정상 실행</li>
 *   <li>{@link #ELEVATED}: 경고 로그 출력, 모니터링 주의 — 아직 작업 폐기 안 함</li>
 *   <li>{@link #CRITICAL}: 비필수 비동기 작업을 즉시 폐기(load shedding)하여
 *       큐 포화를 방지하고 핵심 요청 처리를 보호한다</li>
 * </ul>
 */
public enum PressureLevel {

    /**
     * 큐 사용률 60% 미만 — 모든 작업 정상 실행.
     */
    NORMAL,

    /**
     * 큐 사용률 60~80% — 경고 로그 출력, 운영자 주의 필요.
     * 비필수 작업은 아직 실행되지만, 부하 증가 추세를 알린다.
     */
    ELEVATED,

    /**
     * 큐 사용률 80% 초과 — 비필수 비동기 작업 즉시 폐기.
     * 조회수 증가, 검색 로그 저장 등은 건너뛰고,
     * 주문 후처리 같은 필수 작업은 CallerRunsPolicy로 보장된다.
     */
    CRITICAL
}
