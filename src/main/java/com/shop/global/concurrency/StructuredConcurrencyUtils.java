package com.shop.global.concurrency;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.ThreadFactory;

/**
 * Structured Concurrency (JEP 453) 보조 유틸리티.
 *
 * <p>StructuredTaskScope가 생성하는 가상 스레드에 SecurityContext를 전파하기 위한
 * ThreadFactory를 제공한다. Spring Security의 ThreadLocal 기반 SecurityContext는
 * 가상 스레드에 자동 전파되지 않으므로, StructuredTaskScope 생성 시 이 팩토리를
 * 사용해야 인증 정보가 하위 작업에 전달된다.</p>
 *
 * <p>사용 예시:</p>
 * <pre>
 *   try (var scope = new StructuredTaskScope.ShutdownOnFailure(
 *           "my-scope", StructuredConcurrencyUtils.propagatingThreadFactory())) {
 *       scope.fork(() -&gt; authenticatedServiceCall());
 *       scope.join().throwIfFailed();
 *   }
 * </pre>
 */
public final class StructuredConcurrencyUtils {

    private StructuredConcurrencyUtils() {
    }

    /**
     * 호출 시점의 SecurityContext를 자식 가상 스레드에 전파하는 ThreadFactory를 생성한다.
     *
     * <p>StructuredTaskScope의 fork()가 생성하는 가상 스레드에서
     * SecurityContextHolder.getContext()로 인증 정보를 조회할 수 있게 된다.
     * 스레드 종료 시 SecurityContext를 자동 정리하여 메모리 누수를 방지한다.</p>
     *
     * @return SecurityContext를 전파하는 가상 스레드 ThreadFactory
     */
    public static ThreadFactory propagatingThreadFactory() {
        SecurityContext captured = SecurityContextHolder.getContext();
        return runnable -> Thread.ofVirtual().unstarted(() -> {
            SecurityContextHolder.setContext(captured);
            try {
                runnable.run();
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
    }
}
