package com.shop.domain.search.service.broker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchLogStreamReclaimerTest {

    private StringRedisTemplate redisTemplate;
    @SuppressWarnings("unchecked")
    private final StreamOperations<String, String, String> ops = mock(StreamOperations.class);
    private SearchLogStreamConsumer consumer;
    private SearchLogStreamReclaimer reclaimer;
    private SearchLogBrokerProperties properties;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        doReturn(ops).when(redisTemplate).opsForStream();
        properties = new SearchLogBrokerProperties(
                true, "search-log-stream", "search-log-cg", "consumer-me",
                100, Duration.ofSeconds(1), 100, Duration.ofSeconds(1), 0,
                Duration.ofSeconds(60), Duration.ofSeconds(30), 5, "search-log-dlq");
        consumer = mock(SearchLogStreamConsumer.class);
        reclaimer = new SearchLogStreamReclaimer(redisTemplate, properties, consumer);
    }

    @Test
    @DisplayName("PEL 비어있을 때(summary=null) 조용히 return")
    void noopWhenSummaryNull() {
        when(ops.pending(eq("search-log-stream"), eq("search-log-cg"))).thenReturn(null);

        reclaimer.reclaimOnce();

        verify(consumer, never()).onMessage(any());
        assertThat(reclaimer.getTotalReclaimed()).isZero();
    }

    @Test
    @DisplayName("PEL 총 0건 시 추가 호출 없음")
    void noopWhenZeroPending() {
        PendingMessagesSummary summary = mock(PendingMessagesSummary.class);
        when(summary.getTotalPendingMessages()).thenReturn(0L);
        when(ops.pending(eq("search-log-stream"), eq("search-log-cg"))).thenReturn(summary);

        reclaimer.reclaimOnce();

        verify(ops, never()).pending(anyString(), any(Consumer.class), any(Range.class), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("그룹이 없어 pending 호출이 예외를 던지면 정상으로 간주하고 return")
    void swallowsExceptionWhenGroupMissing() {
        when(ops.pending(eq("search-log-stream"), eq("search-log-cg")))
                .thenThrow(new QueryTimeoutException("group missing"));

        reclaimer.reclaimOnce();

        verify(consumer, never()).onMessage(any());
    }

    @Test
    @DisplayName("idle 임계 미달 메시지는 회수/DLQ 대상이 아님")
    @SuppressWarnings("unchecked")
    void skipsMessagesUnderIdleThreshold() {
        primeSummary(2L);
        PendingMessage pm = pending("100-0", "consumer-me", Duration.ofSeconds(5), 1);
        when(ops.pending(eq("search-log-stream"), any(Consumer.class), any(Range.class), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new PendingMessages("search-log-cg", List.of(pm)));

        reclaimer.reclaimOnce();

        verify(ops, never()).claim(anyString(), anyString(), anyString(), any(Duration.class), any(RecordId[].class));
        verify(consumer, never()).onMessage(any());
        assertThat(reclaimer.getTotalReclaimed()).isZero();
    }

    @Test
    @DisplayName("idle 임계 초과 + 전달 횟수 <= max → XCLAIM 후 컨슈머에 위임")
    @SuppressWarnings("unchecked")
    void claimsAndReprocessesIdleMessage() {
        primeSummary(1L);
        PendingMessage pm = pending("100-0", "consumer-other", Duration.ofSeconds(120), 2);
        when(ops.pending(eq("search-log-stream"), any(Consumer.class), any(Range.class), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new PendingMessages("search-log-cg", List.of(pm)));

        MapRecord<String, String, String> claimedRecord = streamRecord("100-0", "k");
        when(ops.claim(eq("search-log-stream"), eq("search-log-cg"), eq("consumer-me"),
                eq(Duration.ofSeconds(60)), eq(RecordId.of("100-0"))))
                .thenReturn(List.of(claimedRecord));

        reclaimer.reclaimOnce();

        verify(consumer).onMessage(eq(claimedRecord));
        assertThat(reclaimer.getTotalReclaimed()).isEqualTo(1);
        assertThat(reclaimer.getTotalRoutedToDlq()).isZero();
    }

    @Test
    @DisplayName("idle 초과 + 전달 횟수 > max → DLQ 라우팅 + 원본 XACK")
    @SuppressWarnings("unchecked")
    void routesPoisonMessageToDlq() {
        primeSummary(1L);
        // maxDeliveryAttempts=5, 6회 전달된 메시지
        PendingMessage pm = pending("200-0", "consumer-me", Duration.ofSeconds(120), 6);
        when(ops.pending(eq("search-log-stream"), any(Consumer.class), any(Range.class), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new PendingMessages("search-log-cg", List.of(pm)));

        MapRecord<String, String, String> original = streamRecord("200-0", "poison");
        when(ops.range(eq("search-log-stream"), any(Range.class))).thenReturn(List.of(original));

        reclaimer.reclaimOnce();

        // DLQ 스트림에 XADD
        ArgumentCaptor<MapRecord<String, String, String>> dlqCaptor = ArgumentCaptor.forClass(MapRecord.class);
        verify(ops).add(dlqCaptor.capture());
        assertThat(dlqCaptor.getValue().getStream()).isEqualTo("search-log-dlq");
        assertThat(dlqCaptor.getValue().getValue()).containsEntry("keyword", "poison");

        // 원본 XACK
        verify(ops).acknowledge(eq("search-log-stream"), eq("search-log-cg"), eq(RecordId.of("200-0")));
        verify(consumer, never()).onMessage(any());
        assertThat(reclaimer.getTotalRoutedToDlq()).isEqualTo(1);
    }

    @Test
    @DisplayName("DLQ 라우팅 — 원본이 이미 삭제됐으면 XACK 만 수행")
    @SuppressWarnings("unchecked")
    void dlqRoutingHandlesMissingOriginal() {
        primeSummary(1L);
        PendingMessage pm = pending("300-0", "consumer-me", Duration.ofSeconds(120), 6);
        when(ops.pending(eq("search-log-stream"), any(Consumer.class), any(Range.class), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new PendingMessages("search-log-cg", List.of(pm)));

        when(ops.range(eq("search-log-stream"), any(Range.class))).thenReturn(Collections.emptyList());

        reclaimer.reclaimOnce();

        verify(ops, never()).add(any(MapRecord.class));
        verify(ops).acknowledge(eq("search-log-stream"), eq("search-log-cg"), eq(RecordId.of("300-0")));
        assertThat(reclaimer.getTotalRoutedToDlq()).isZero();
    }

    @Test
    @DisplayName("본 컨슈머 PEL 비어있으면 다른 컨슈머의 idle 메시지를 회수한다 (자기 메시지는 skip)")
    @SuppressWarnings("unchecked")
    void scansAcrossConsumersWhenOwnPelEmpty() {
        primeSummary(2L);
        // 본 컨슈머 PEL → empty
        when(ops.pending(eq("search-log-stream"), any(Consumer.class), any(Range.class), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new PendingMessages("search-log-cg", Collections.emptyList()));
        // 그룹 전체 PEL — 자기 1건 + 다른 컨슈머 1건
        PendingMessage mine = pending("100-0", "consumer-me", Duration.ofSeconds(120), 1);
        PendingMessage theirs = pending("100-1", "consumer-other", Duration.ofSeconds(120), 1);
        when(ops.pending(eq("search-log-stream"), eq("search-log-cg"), any(Range.class), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new PendingMessages("search-log-cg", List.of(mine, theirs)));

        MapRecord<String, String, String> claimedFromOther = streamRecord("100-1", "k");
        when(ops.claim(eq("search-log-stream"), eq("search-log-cg"), eq("consumer-me"),
                any(Duration.class), eq(RecordId.of("100-1"))))
                .thenReturn(List.of(claimedFromOther));

        reclaimer.reclaimOnce();

        // 자기 메시지(mine) 는 claim 호출 안 됨
        verify(ops, never()).claim(anyString(), anyString(), anyString(),
                any(Duration.class), eq(RecordId.of("100-0")));
        // 다른 컨슈머 메시지(theirs) 는 claim 호출
        verify(consumer).onMessage(eq(claimedFromOther));
        assertThat(reclaimer.getTotalReclaimed()).isEqualTo(1);
    }

    @Test
    @DisplayName("scanPendingEntries 는 reclaimOnce 의 예외를 잡고 다음 스케줄을 살린다")
    void schedulerSwallowsExceptions() {
        when(ops.pending(eq("search-log-stream"), eq("search-log-cg")))
                .thenThrow(new QueryTimeoutException("Redis down"))
                .thenReturn(null);

        // 예외가 새지 않아야 한다.
        reclaimer.scanPendingEntries();

        // 다음 호출은 정상 흐름.
        reclaimer.scanPendingEntries();
        verify(ops, org.mockito.Mockito.times(2)).pending(eq("search-log-stream"), eq("search-log-cg"));
    }

    @Test
    @DisplayName("XACK 실패는 조용히 삼킨다(메트릭만 잃음)")
    @SuppressWarnings("unchecked")
    void ackFailureIsSwallowed() {
        primeSummary(1L);
        PendingMessage pm = pending("400-0", "consumer-me", Duration.ofSeconds(120), 6);
        when(ops.pending(eq("search-log-stream"), any(Consumer.class), any(Range.class), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new PendingMessages("search-log-cg", List.of(pm)));
        when(ops.range(eq("search-log-stream"), any(Range.class)))
                .thenReturn(List.of(streamRecord("400-0", "poison")));
        doThrow(new QueryTimeoutException("ack failed"))
                .when(ops).acknowledge(anyString(), anyString(), any(RecordId[].class));

        // 예외 없이 종료해야 함
        reclaimer.reclaimOnce();

        // DLQ 라우팅 카운터는 증가했음(라우팅 자체는 성공)
        assertThat(reclaimer.getTotalRoutedToDlq()).isEqualTo(1);
    }

    @Test
    @DisplayName("ensureGroupExists — BUSYGROUP 예외는 무시(이미 존재)")
    void ensureGroupExistsIgnoresBusyGroup() {
        doThrow(new RuntimeException(new RuntimeException("BUSYGROUP Consumer Group already exists")))
                .when(ops).createGroup(anyString(), any(), anyString());

        // 예외 없이 종료해야 함
        SearchLogStreamReclaimer.ensureGroupExists(redisTemplate, "search-log-stream", "search-log-cg");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void primeSummary(long totalPending) {
        PendingMessagesSummary summary = mock(PendingMessagesSummary.class);
        when(summary.getTotalPendingMessages()).thenReturn(totalPending);
        when(ops.pending(eq("search-log-stream"), eq("search-log-cg"))).thenReturn(summary);
    }

    private static PendingMessage pending(String id, String consumerName, Duration idle, long deliveryCount) {
        return new PendingMessage(
                RecordId.of(id),
                Consumer.from("search-log-cg", consumerName),
                idle,
                deliveryCount);
    }

    private static MapRecord<String, String, String> streamRecord(String id, String keyword) {
        return StreamRecords.mapBacked(Map.of(
                "userId", "",
                "keyword", keyword,
                "resultCount", "0",
                "ipAddress", "",
                "userAgent", "",
                "searchedAt", "2026-05-17T12:00:00"
        )).withStreamKey("search-log-stream").withId(RecordId.of(id));
    }
}
