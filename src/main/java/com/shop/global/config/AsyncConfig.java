package com.shop.global.config;

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


        executor.setRejectedExecutionHandler((runnable, threadPoolExecutor) -> {
            asyncExecutorMetrics.incrementRejected();
            new ThreadPoolExecutor.AbortPolicy().rejectedExecution(runnable, threadPoolExecutor);
        });

        executor.initialize();
        asyncExecutorMetrics.bindExecutor(executor);

        return executor;
    }
}
