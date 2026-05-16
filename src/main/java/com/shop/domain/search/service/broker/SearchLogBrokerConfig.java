package com.shop.domain.search.service.broker;

import com.shop.domain.search.service.SearchLogBatchWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;

import java.time.Duration;

/**
 * [Phase 21] 검색 로그 Redis Streams 브로커 빈 구성.
 *
 * <h3>활성화 조건</h3>
 * <ul>
 *   <li>{@code @Profile("redis")} — Redis 인프라가 있는 프로파일에서만 후보가 됨</li>
 *   <li>{@code @ConditionalOnProperty("app.search-log.broker.enabled=true")} — opt-in 플래그</li>
 * </ul>
 *
 * <p>두 조건이 모두 충족돼야 빈이 등록된다. 비활성 시(기본) 본 패키지의 어떤 빈도 컨텍스트에
 * 올라오지 않으므로 기존 인메모리+WAL 경로가 그대로 동작한다.</p>
 *
 * <h3>구성 요소</h3>
 * <ol>
 *   <li>{@link SearchLogStreamProducer} — XADD</li>
 *   <li>{@link SearchLogStreamConsumer} — 단건 INSERT + XACK</li>
 *   <li>{@link SearchLogStreamReclaimer} — PEL 회수 + DLQ</li>
 *   <li>{@link StreamMessageListenerContainer} — XREADGROUP 폴링 컨테이너</li>
 *   <li>{@link StreamContainerLifecycle} — 그룹 생성·컨테이너 start/stop</li>
 * </ol>
 */
@Configuration
@Profile("redis")
@ConditionalOnProperty(prefix = "app.search-log.broker", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(SearchLogBrokerProperties.class)
public class SearchLogBrokerConfig {

    @Bean
    public SearchLogStreamProducer searchLogStreamProducer(
            StringRedisTemplate redisTemplate,
            SearchLogBrokerProperties properties) {
        return new SearchLogStreamProducer(redisTemplate, properties);
    }

    @Bean
    public SearchLogStreamConsumer.RedisStreamAcker redisStreamAcker(StringRedisTemplate redisTemplate) {
        return (stream, group, ids) ->
                redisTemplate.opsForStream().acknowledge(stream, group, ids);
    }

    @Bean
    public SearchLogStreamConsumer searchLogStreamConsumer(
            SearchLogBatchWriter writer,
            SearchLogBrokerProperties properties,
            SearchLogStreamConsumer.RedisStreamAcker acker) {
        return new SearchLogStreamConsumer(writer, properties, acker);
    }

    @Bean
    public SearchLogStreamReclaimer searchLogStreamReclaimer(
            StringRedisTemplate redisTemplate,
            SearchLogBrokerProperties properties,
            SearchLogStreamConsumer consumer) {
        return new SearchLogStreamReclaimer(redisTemplate, properties, consumer);
    }

    @Bean
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> searchLogStreamContainer(
            RedisConnectionFactory connectionFactory,
            SearchLogBrokerProperties properties) {

        StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                <String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                        .builder()
                        .batchSize(properties.pollBatchSize())
                        .pollTimeout(properties.pollBlock())
                        // Lettuce 의 LettuceConnectionFactory 와 호환되는 기본 KeySerializer (String)
                        .build();
        return StreamMessageListenerContainer.create(connectionFactory, options);
    }

    @Bean
    public SearchLogBrokerMeterBinder searchLogBrokerMeterBinder(
            SearchLogStreamProducer producer,
            SearchLogStreamConsumer consumer,
            SearchLogStreamReclaimer reclaimer,
            StringRedisTemplate redisTemplate,
            SearchLogBrokerProperties properties) {
        return new SearchLogBrokerMeterBinder(producer, consumer, reclaimer, redisTemplate, properties);
    }

    @Bean
    public StreamContainerLifecycle searchLogStreamContainerLifecycle(
            StringRedisTemplate redisTemplate,
            SearchLogBrokerProperties properties,
            SearchLogStreamConsumer consumer,
            StreamMessageListenerContainer<String, MapRecord<String, String, String>> container) {
        return new StreamContainerLifecycle(redisTemplate, properties, consumer, container);
    }

    /**
     * 그룹 생성 → 구독 등록 → 컨테이너 start (init), 컨테이너 stop (destroy).
     */
    public static final class StreamContainerLifecycle implements InitializingBean, DisposableBean {

        private static final Logger log = LoggerFactory.getLogger(StreamContainerLifecycle.class);

        private final StringRedisTemplate redisTemplate;
        private final SearchLogBrokerProperties properties;
        private final SearchLogStreamConsumer consumer;
        private final StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
        private Subscription subscription;

        public StreamContainerLifecycle(
                StringRedisTemplate redisTemplate,
                SearchLogBrokerProperties properties,
                SearchLogStreamConsumer consumer,
                StreamMessageListenerContainer<String, MapRecord<String, String, String>> container) {
            this.redisTemplate = redisTemplate;
            this.properties = properties;
            this.consumer = consumer;
            this.container = container;
        }

        @Override
        public void afterPropertiesSet() {
            SearchLogStreamReclaimer.ensureGroupExists(
                    redisTemplate, properties.stream(), properties.consumerGroup());

            subscription = container.receive(
                    Consumer.from(properties.consumerGroup(), properties.consumerName()),
                    StreamOffset.create(properties.stream(), ReadOffset.lastConsumed()),
                    consumer);

            container.start();
            try {
                subscription.await(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.info("[Phase 21] 검색 로그 스트림 컨슈머 기동 — stream={}, group={}, consumer={}",
                    properties.stream(), properties.consumerGroup(), properties.consumerName());
        }

        @Override
        public void destroy() {
            if (subscription != null) {
                subscription.cancel();
            }
            container.stop();
            log.info("[Phase 21] 검색 로그 스트림 컨슈머 중지 — stream={}, consumer={}",
                    properties.stream(), properties.consumerName());
        }
    }
}
