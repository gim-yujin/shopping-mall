package com.shop.global.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    private final int awaitTerminationSeconds;

    public AsyncConfig(@Value("${app.async.await-termination-seconds:30}") int awaitTerminationSeconds) {
        this.awaitTerminationSeconds = awaitTerminationSeconds;
    }

    @Bean
    public AsyncExecutorMetrics asyncExecutorMetrics() {
        return new AsyncExecutorMetrics();
    }

    @Bean(name = "asyncExecutor")
    public Executor asyncExecutor(AsyncExecutorMetrics asyncExecutorMetrics) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("async-");

        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds);


        // [Phase 12] Graceful Degradation: AbortPolicy → 로그 + 폐기 정책으로 전환.
        //
        // 변경 전(AbortPolicy):
        //   큐가 가득 차면 RejectedExecutionException이 호출 스레드(HTTP 요청 스레드)로
        //   전파되어, 비필수 비동기 작업(조회수 증가, 검색 로그)의 실패가
        //   200 응답을 500 에러로 바꾸는 연쇄 장애를 유발했다.
        //
        // 변경 후(DiscardPolicy + 로그):
        //   큐 오버플로 시 작업을 조용히 폐기하고 거부 카운터만 증가시킨다.
        //   HTTP 요청 스레드는 영향을 받지 않으며, 사용자 응답은 정상 반환된다.
        //   거부된 작업 수는 AsyncExecutorMetrics → BackpressureHealthIndicator를 통해
        //   /actuator/health에서 모니터링할 수 있다.
        //
        // 주의: 이 정책은 asyncExecutor(비필수 작업 전용)에만 적용된다.
        //   orderPostProcessExecutor는 CallerRunsPolicy를 유지하여 작업 유실을 방지한다.
        executor.setRejectedExecutionHandler((runnable, threadPoolExecutor) -> {
            asyncExecutorMetrics.incrementRejected();
            log.warn("[Phase 12] 비동기 작업 폐기 — 큐 포화 (queue.size={}, rejected.total={})",
                    threadPoolExecutor.getQueue().size(),
                    asyncExecutorMetrics.getRejectedTotal());
        });

        executor.initialize();
        asyncExecutorMetrics.bindExecutor(executor);

        return executor;
    }

    /**
     * [Phase 6] 주문 후처리 전용 스레드 풀.
     *
     * <p><b>문제:</b> 단일 asyncExecutor를 모든 비동기 작업(조회수 증가, 검색 로그,
     * 주문 후처리)이 공유하면, 주문 폭증 시 등급 재계산 작업이 큐에 쌓여
     * 조회수/검색 로그까지 지연되는 리소스 간섭(noisy neighbor) 문제가 발생한다.</p>
     *
     * <p><b>해결:</b> 주문 후처리 전용 풀을 분리하여 격리(bulkhead) 패턴을 적용한다.
     * core=2, max=4, queue=200으로 설정하여 주문 후처리가 폭증해도
     * 기존 asyncExecutor의 가용성에 영향을 주지 않는다.</p>
     *
     * <p><b>RejectedExecutionHandler:</b> CallerRunsPolicy를 사용한다.
     * 큐가 가득 차면 호출 스레드(이벤트 발행 스레드)에서 직접 실행하여 작업 유실을 방지한다.
     * AbortPolicy(기존 asyncExecutor)와 다른 이유: 조회수 증가는 유실되어도
     * 비즈니스 영향이 없지만, 등급 재계산은 유실되면 사용자 등급이 부정확해진다.</p>
     */
    @Bean(name = "orderPostProcessExecutor")
    public Executor orderPostProcessExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("order-post-");

        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds);

        // CallerRunsPolicy: 큐가 가득 차면 호출 스레드에서 실행하여 작업 유실 방지
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        executor.initialize();
        return executor;
    }
}
