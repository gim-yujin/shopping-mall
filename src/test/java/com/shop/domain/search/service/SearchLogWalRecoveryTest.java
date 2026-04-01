package com.shop.domain.search.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * SearchLogWalRecovery 단위 테스트.
 *
 * <p>WAL 복구 흐름(recover → writeBatch → deleteRecoveredSegments)과
 * 에러 핸들링(배치 실패 시 dropped 카운팅)을 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class SearchLogWalRecoveryTest {

    @Mock private SearchLogWalManager walManager;
    @Mock private SearchLogBatchWriter batchWriter;

    @InjectMocks
    private SearchLogWalRecovery recovery;

    private SearchLogEntry sampleEntry(String keyword) {
        return new SearchLogEntry(1L, keyword, 10, "127.0.0.1", "Mozilla", LocalDateTime.now());
    }

    @Test
    @DisplayName("복구 엔트리가 비어있으면 — writeBatch, deleteRecoveredSegments를 호출하지 않는다")
    void run_emptyRecovery_skipsProcessing() throws Exception {
        when(walManager.recoverAll()).thenReturn(List.of());

        recovery.run(new DefaultApplicationArguments());

        verify(batchWriter, never()).writeBatch(anyList());
        verify(walManager, never()).deleteRecoveredSegments();
    }

    @Test
    @DisplayName("정상 복구 — 배치 단위로 DB에 저장 후 세그먼트를 삭제한다")
    void run_normalRecovery_writesAndDeletesSegments() throws Exception {
        List<SearchLogEntry> entries = List.of(
                sampleEntry("키워드1"), sampleEntry("키워드2"));
        when(walManager.recoverAll()).thenReturn(entries);
        when(batchWriter.writeBatch(anyList())).thenReturn(2);

        recovery.run(new DefaultApplicationArguments());

        verify(batchWriter).writeBatch(entries);
        verify(walManager).deleteRecoveredSegments();
    }

    @Test
    @DisplayName("배치 저장 실패 — 해당 배치를 dropped로 처리하고 세그먼트는 삭제한다")
    void run_batchWriteFails_continuesAndDeletesSegments() throws Exception {
        List<SearchLogEntry> entries = List.of(sampleEntry("키워드1"));
        when(walManager.recoverAll()).thenReturn(entries);
        when(batchWriter.writeBatch(anyList())).thenThrow(new RuntimeException("DB 장애"));

        recovery.run(new DefaultApplicationArguments());

        // 실패해도 세그먼트는 삭제 (무한 복구 루프 방지)
        verify(walManager).deleteRecoveredSegments();
    }

    @Test
    @DisplayName("partition — 리스트를 지정된 크기의 서브리스트로 분할한다")
    void partition_splitsList() {
        List<Integer> list = List.of(1, 2, 3, 4, 5);

        List<List<Integer>> result = SearchLogWalRecovery.partition(list, 2);

        assertThat(result).hasSize(3);
        assertThat(result.get(0)).containsExactly(1, 2);
        assertThat(result.get(1)).containsExactly(3, 4);
        assertThat(result.get(2)).containsExactly(5);
    }

    @Test
    @DisplayName("partition — 빈 리스트는 빈 결과를 반환한다")
    void partition_emptyList_returnsEmpty() {
        List<List<Integer>> result = SearchLogWalRecovery.partition(List.of(), 100);

        assertThat(result).isEmpty();
    }
}
