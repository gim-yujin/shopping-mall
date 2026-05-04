package com.shop.domain.search.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Phase 20] SearchLogWalManager 단위 테스트.
 *
 * <h3>검증 범위</h3>
 * <ul>
 *   <li>append — WAL 세그먼트에 JSON Lines 형식으로 엔트리 기록</li>
 *   <li>rotateSegment — 현재 세그먼트 봉인 + 새 세그먼트 생성</li>
 *   <li>recoverAll — 잔존 세그먼트에서 엔트리 복구</li>
 *   <li>deleteSegment — DB flush 후 세그먼트 삭제</li>
 *   <li>크래시 시뮬레이션 — rotate 후 삭제 전 잔존 세그먼트 복구</li>
 *   <li>손상된 세그먼트 — 파싱 불가 라인을 건너뛰고 나머지 복구</li>
 * </ul>
 *
 * <p>{@code @TempDir}로 각 테스트마다 독립된 임시 디렉터리를 사용하여
 * 테스트 간 간섭을 방지한다.</p>
 */
class SearchLogWalManagerTest {

    @TempDir
    Path tempDir;

    private SearchLogWalManager walManager;

    @BeforeEach
    void setUp() {
        walManager = new SearchLogWalManager(tempDir, false);
    }

    @AfterEach
    void tearDown() {
        walManager.close();
    }

    private SearchLogEntry createEntry(String keyword) {
        return new SearchLogEntry(1L, keyword, 10, "127.0.0.1", "JUnit",
                LocalDateTime.of(2026, 3, 29, 12, 0, 0));
    }

    // ──────────── append 테스트 ────────────

    @Nested
    @DisplayName("append — WAL 세그먼트 기록")
    class AppendTests {

        @Test
        @DisplayName("단일 엔트리 기록 → 세그먼트 파일에 JSON 1줄 존재")
        void append_singleEntry_writesOneJsonLine() throws IOException {
            walManager.append(createEntry("노트북"));

            // 세그먼트 파일이 1개 존재하고, 내용이 1줄이어야 한다
            List<Path> segments = listSegments();
            assertThat(segments).hasSize(1);

            // flush를 위해 rotate 후 내용 확인 (BufferedWriter 특성상 close/flush 필요)
            walManager.close();
            List<String> lines = Files.readAllLines(segments.get(0), StandardCharsets.UTF_8);
            assertThat(lines).hasSize(1);
            assertThat(lines.get(0)).contains("노트북");
        }

        @Test
        @DisplayName("여러 엔트리 기록 → 엔트리 수만큼 JSON Lines 존재")
        void append_multipleEntries_writesMultipleLines() throws IOException {
            walManager.append(createEntry("노트북"));
            walManager.append(createEntry("키보드"));
            walManager.append(createEntry("마우스"));

            walManager.close();
            List<Path> segments = listSegments();
            assertThat(segments).hasSize(1);

            List<String> lines = Files.readAllLines(segments.get(0), StandardCharsets.UTF_8);
            assertThat(lines).hasSize(3);
        }

        @Test
        @DisplayName("walBytesWritten 메트릭 증가")
        void append_incrementsMetric() {
            walManager.append(createEntry("노트북"));

            assertThat(walManager.getWalBytesWritten()).isGreaterThan(0);
        }
    }

    // ──────────── rotateSegment 테스트 ────────────

    @Nested
    @DisplayName("rotateSegment — 세그먼트 전환")
    class RotateTests {

        @Test
        @DisplayName("엔트리 있는 세그먼트 rotate → 이전 세그먼트 경로 반환 + 새 세그먼트 생성")
        void rotate_withEntries_returnsOldSegmentAndCreatesNew() {
            walManager.append(createEntry("노트북"));

            Path oldSegment = walManager.rotateSegment();

            assertThat(oldSegment).isNotNull();
            assertThat(Files.exists(oldSegment)).isTrue();

            // 새 세그먼트가 생성되어 총 2개의 세그먼트 파일이 존재
            List<Path> segments = listSegments();
            assertThat(segments).hasSize(2);
        }

        @Test
        @DisplayName("빈 세그먼트 rotate → null 반환, 빈 세그먼트 자동 삭제")
        void rotate_emptySegment_returnsNull() {
            // 아무것도 append하지 않고 rotate
            Path oldSegment = walManager.rotateSegment();

            assertThat(oldSegment).isNull();

            // 빈 세그먼트는 삭제되고, 새 세그먼트만 존재
            List<Path> segments = listSegments();
            assertThat(segments).hasSize(1);
        }

        @Test
        @DisplayName("rotate 후 새 엔트리는 새 세그먼트에 기록")
        void rotate_newEntriesGoToNewSegment() throws IOException {
            walManager.append(createEntry("노트북"));
            Path oldSegment = walManager.rotateSegment();
            walManager.append(createEntry("키보드"));

            walManager.close();

            // 이전 세그먼트: 1줄, 새 세그먼트: 1줄
            List<String> oldLines = Files.readAllLines(oldSegment, StandardCharsets.UTF_8);
            assertThat(oldLines).hasSize(1);
            assertThat(oldLines.get(0)).contains("노트북");

            // 새 세그먼트 찾기 (oldSegment이 아닌 것)
            List<Path> segments = listSegments();
            Path newSegment = segments.stream()
                    .filter(p -> !p.equals(oldSegment))
                    .findFirst()
                    .orElseThrow();
            List<String> newLines = Files.readAllLines(newSegment, StandardCharsets.UTF_8);
            assertThat(newLines).hasSize(1);
            assertThat(newLines.get(0)).contains("키보드");
        }
    }

    // ──────────── deleteSegment 테스트 ────────────

    @Nested
    @DisplayName("deleteSegment — 세그먼트 삭제")
    class DeleteTests {

        @Test
        @DisplayName("세그먼트 삭제 → 파일이 제거됨")
        void delete_removesFile() {
            walManager.append(createEntry("노트북"));
            Path segment = walManager.rotateSegment();

            walManager.deleteSegment(segment);

            assertThat(Files.exists(segment)).isFalse();
        }

        @Test
        @DisplayName("null 전달 시 예외 없음")
        void delete_nullPath_noException() {
            walManager.deleteSegment(null);
            // 예외 없이 통과
        }
    }

    // ──────────── recoverAll 테스트 ────────────

    @Nested
    @DisplayName("recoverAll — WAL 복구")
    class RecoverTests {

        @Test
        @DisplayName("잔존 세그먼트 없음 → 빈 리스트 반환")
        void recover_noSegments_returnsEmpty() {
            List<SearchLogEntry> recovered = walManager.recoverAll();

            assertThat(recovered).isEmpty();
            assertThat(walManager.getRecoveredCount()).isZero();
        }

        @Test
        @DisplayName("잔존 세그먼트 1개 → 모든 엔트리 복구")
        void recover_oneSegment_recoversAllEntries() {
            // 세그먼트에 3건 기록 후 rotate (봉인)
            walManager.append(createEntry("노트북"));
            walManager.append(createEntry("키보드"));
            walManager.append(createEntry("마우스"));
            walManager.rotateSegment();

            // 새 WalManager로 복구 시뮬레이션 (기동 직후 상태)
            walManager.close();
            SearchLogWalManager newManager = new SearchLogWalManager(tempDir, false);

            List<SearchLogEntry> recovered = newManager.recoverAll();
            newManager.close();

            assertThat(recovered).hasSize(3);
            assertThat(recovered.get(0).keyword()).isEqualTo("노트북");
            assertThat(recovered.get(1).keyword()).isEqualTo("키보드");
            assertThat(recovered.get(2).keyword()).isEqualTo("마우스");
            assertThat(newManager.getRecoveredCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("잔존 세그먼트 여러 개 → 모두 복구")
        void recover_multipleSegments_recoversAll() {
            // 세그먼트 2개 생성
            walManager.append(createEntry("노트북"));
            walManager.rotateSegment();
            walManager.append(createEntry("키보드"));
            walManager.rotateSegment();

            walManager.close();
            SearchLogWalManager newManager = new SearchLogWalManager(tempDir, false);

            List<SearchLogEntry> recovered = newManager.recoverAll();
            newManager.close();

            assertThat(recovered).hasSize(2);
        }

        @Test
        @DisplayName("deleteRecoveredSegments — 복구 후 세그먼트 삭제")
        void recover_thenDelete_removesSegments() {
            walManager.append(createEntry("노트북"));
            walManager.rotateSegment();

            walManager.close();
            SearchLogWalManager newManager = new SearchLogWalManager(tempDir, false);

            newManager.recoverAll();
            newManager.deleteRecoveredSegments();

            // 현재 활성 세그먼트(newManager가 생성한 것)만 남아야 함
            List<Path> remainingSegments = listSegments();
            assertThat(remainingSegments).hasSize(1);

            newManager.close();
        }
    }

    // ──────────── 크래시 시뮬레이션 ────────────

    @Nested
    @DisplayName("크래시 시뮬레이션 — rotate 후 삭제 전 크래시")
    class CrashSimulationTests {

        @Test
        @DisplayName("rotate 후 삭제 전 크래시 → 다음 기동에서 세그먼트 복구")
        void crash_afterRotateBeforeDelete_recoversOnNextStartup() {
            // 정상 플러시 흐름: append → rotate → (DB flush) → delete
            walManager.append(createEntry("노트북"));
            walManager.append(createEntry("키보드"));
            Path oldSegment = walManager.rotateSegment();

            // ★ 여기서 크래시 발생 — deleteSegment() 호출 전에 프로세스 종료
            // oldSegment는 디스크에 남아 있음
            assertThat(Files.exists(oldSegment)).isTrue();
            walManager.close();

            // 프로세스 재기동 시뮬레이션
            SearchLogWalManager newManager = new SearchLogWalManager(tempDir, false);
            List<SearchLogEntry> recovered = newManager.recoverAll();
            newManager.close();

            assertThat(recovered).hasSize(2);
            assertThat(recovered.get(0).keyword()).isEqualTo("노트북");
            assertThat(recovered.get(1).keyword()).isEqualTo("키보드");
        }

        @Test
        @DisplayName("append 중 크래시 → 불완전 라인을 건너뛰고 정상 라인 복구")
        void crash_duringAppend_recoversCompleteLines() throws IOException {
            // 정상 엔트리 2건 기록
            walManager.append(createEntry("노트북"));
            walManager.append(createEntry("키보드"));

            // 세그먼트를 봉인한 뒤 수동으로 불완전 라인 추가 (크래시 시뮬레이션)
            Path segment = walManager.rotateSegment();
            try (BufferedWriter bw = Files.newBufferedWriter(segment, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND)) {
                bw.write("{\"userId\":1,\"keyword\":\"불완전 JSON");
                bw.newLine();
            }

            walManager.close();

            // 복구: 정상 2건만 복구, 불완전 1건은 건너뜀
            SearchLogWalManager newManager = new SearchLogWalManager(tempDir, false);
            List<SearchLogEntry> recovered = newManager.recoverAll();
            newManager.close();

            assertThat(recovered).hasSize(2);
        }

        @Test
        @DisplayName("빈 라인이 포함된 세그먼트 → 빈 라인 무시하고 정상 복구")
        void recover_withEmptyLines_ignoresEmptyLines() throws IOException {
            walManager.append(createEntry("노트북"));
            Path segment = walManager.rotateSegment();

            // 빈 라인 삽입
            try (BufferedWriter bw = Files.newBufferedWriter(segment, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND)) {
                bw.newLine();
                bw.newLine();
            }

            walManager.close();
            SearchLogWalManager newManager = new SearchLogWalManager(tempDir, false);
            List<SearchLogEntry> recovered = newManager.recoverAll();
            newManager.close();

            assertThat(recovered).hasSize(1);
        }
    }

    // ──────────── syncOnAppend 테스트 ────────────

    @Nested
    @DisplayName("syncOnAppend — 즉시 flush 모드")
    class SyncOnAppendTests {

        @Test
        @DisplayName("syncOnAppend=true → append 직후 파일에 내용 존재")
        void syncOnAppend_writesImmediately() throws IOException {
            SearchLogWalManager syncManager = new SearchLogWalManager(tempDir.resolve("sync"), true);

            syncManager.append(createEntry("노트북"));

            // sync 모드에서는 append 직후 파일에 내용이 있어야 한다 (flush 없이)
            List<Path> segments = listSegments(tempDir.resolve("sync"));
            assertThat(segments).hasSize(1);

            long fileSize = Files.size(segments.get(0));
            assertThat(fileSize).isGreaterThan(0);

            syncManager.close();
        }
    }

    // ──────────── WAL Recovery 파티션 유틸 테스트 ────────────

    @Nested
    @DisplayName("SearchLogWalRecovery.partition — 리스트 분할")
    class PartitionTests {

        @Test
        @DisplayName("리스트 크기가 batchSize의 배수 → 균등 분할")
        void partition_evenSplit() {
            List<Integer> list = List.of(1, 2, 3, 4, 5, 6);
            List<List<Integer>> result = SearchLogWalRecovery.partition(list, 3);

            assertThat(result).hasSize(2);
            assertThat(result.get(0)).containsExactly(1, 2, 3);
            assertThat(result.get(1)).containsExactly(4, 5, 6);
        }

        @Test
        @DisplayName("리스트 크기가 batchSize 미만 → 단일 파티션")
        void partition_smallerThanBatch() {
            List<Integer> list = List.of(1, 2);
            List<List<Integer>> result = SearchLogWalRecovery.partition(list, 5);

            assertThat(result).hasSize(1);
            assertThat(result.get(0)).containsExactly(1, 2);
        }

        @Test
        @DisplayName("나머지가 있는 분할 → 마지막 파티션에 나머지 포함")
        void partition_withRemainder() {
            List<Integer> list = List.of(1, 2, 3, 4, 5);
            List<List<Integer>> result = SearchLogWalRecovery.partition(list, 3);

            assertThat(result).hasSize(2);
            assertThat(result.get(0)).containsExactly(1, 2, 3);
            assertThat(result.get(1)).containsExactly(4, 5);
        }

        @Test
        @DisplayName("빈 리스트 → 빈 결과")
        void partition_emptyList() {
            List<Integer> list = List.of();
            List<List<Integer>> result = SearchLogWalRecovery.partition(list, 3);

            assertThat(result).isEmpty();
        }
    }

    // ──────────── 헬퍼 메서드 ────────────

    private List<Path> listSegments() {
        return listSegments(tempDir);
    }

    private List<Path> listSegments(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith(SearchLogWalManager.SEGMENT_PREFIX))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
