package com.shop.domain.search.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 검색 로그 Write-Ahead Log (WAL) 관리자.
 *
 * <h3>문제</h3>
 * <p>{@link SearchLogBatchAccumulator}는 검색 로그를 인메모리 {@code ConcurrentLinkedQueue}에
 * 누적한 뒤 주기적(5초)으로 DB에 배치 INSERT한다. Graceful Shutdown 시에는
 * {@code DisposableBean.destroy()}가 잔여 버퍼를 플러시하지만,
 * <b>프로세스 비정상 종료</b>(kill -9, OOM Killer, 하드웨어 장애) 시에는
 * 인메모리 버퍼의 데이터가 복구 불가능하게 유실된다.</p>
 *
 * <p>최대 5초(플러시 주기) × 초당 검색 건수만큼의 로그가 유실될 수 있으며,
 * 인기 검색어 통계의 정확도에 직접적인 영향을 준다.</p>
 *
 * <h3>해결: 세그먼트 기반 WAL</h3>
 * <p>검색 로그 엔트리를 인메모리 버퍼에 추가하기 전에 디스크의 WAL 파일(세그먼트)에
 * 먼저 기록한다. 프로세스가 비정상 종료되어도 WAL 세그먼트가 디스크에 남아 있으므로,
 * 다음 기동 시 {@link SearchLogWalRecovery}가 잔존 세그먼트를 읽어 DB에 복원한다.</p>
 *
 * <h3>세그먼트 생명주기</h3>
 * <pre>
 * 1. 신규 세그먼트 생성 → 현재 세그먼트로 지정
 * 2. add() 호출마다 현재 세그먼트에 JSON Lines 형식으로 append
 * 3. flush() 시점에 rotateSegment(): 현재 세그먼트를 닫고 새 세그먼트를 연다
 * 4. DB flush 성공 후 deleteSegment(): 닫힌 세그먼트를 삭제
 * 5. 기동 시 recoverAll(): 잔존 세그먼트(3~4 사이 크래시)를 읽어 복구
 * </pre>
 *
 * <h3>복구 시 중복 가능성</h3>
 * <p>크래시 타이밍에 따라 이미 DB에 저장된 엔트리가 WAL에도 남아 있을 수 있다.
 * 복구 시 해당 엔트리가 다시 INSERT되어 소수의 중복이 발생할 수 있으나,
 * 검색 로그는 인기 검색어 통계 목적이므로 소수의 중복은 통계 정확도에 무시할 수준이다.
 * 정확한 exactly-once를 보장하려면 Kafka + idempotency key가 필요하지만,
 * 외부 인프라 없이 내구성을 확보하는 것이 이 구현의 목표이다.</p>
 *
 * <h3>파일 형식</h3>
 * <p>각 세그먼트는 JSON Lines 형식으로, 한 줄에 하나의 {@link SearchLogEntry}를 JSON으로 직렬화한다.
 * 파싱 실패한 라인은 건너뛰어 부분 손상된 세그먼트에서도 최대한 복구한다.</p>
 *
 * <h3>스레드 안전성</h3>
 * <p>{@code append()}와 {@code rotateSegment()}는 {@code synchronized}로 상호 배제한다.
 * {@code ConcurrentLinkedQueue.offer()}(lock-free CAS)에 비해 동기화 비용이 있지만,
 * BufferedWriter의 메모리 복사는 &lt;1μs 수준이므로 contention은 무시할 수준이다.</p>
 *
 * @see SearchLogBatchAccumulator#add(SearchLogEntry) WAL append가 호출되는 지점
 * @see SearchLogWalRecovery 기동 시 WAL 복구를 수행하는 컴포넌트
 */
public class SearchLogWalManager {

    private static final Logger log = LoggerFactory.getLogger(SearchLogWalManager.class);

    /** WAL 세그먼트 파일명 접두사. 이 접두사로 시작하는 파일만 WAL로 인식한다. */
    static final String SEGMENT_PREFIX = "wal-";

    /** WAL 세그먼트 파일명 접미사. */
    static final String SEGMENT_SUFFIX = ".log";

    private final Path walDir;

    /**
     * Jackson ObjectMapper — SearchLogEntry record를 JSON으로 직렬화/역직렬화한다.
     * JavaTimeModule을 등록하여 LocalDateTime을 ISO-8601 형식으로 처리한다.
     * 인스턴스 변수로 유지하여 매 호출마다 생성하는 비용을 제거한다.
     */
    private final ObjectMapper objectMapper;

    /**
     * true이면 매 append 후 BufferedWriter.flush()를 호출하여
     * 엔트리가 OS 파일 버퍼에 즉시 반영되도록 한다.
     * false이면 BufferedWriter의 기본 버퍼링(8KB)에 맡겨 처리량을 우선한다.
     * 어느 경우든 OS 크래시(하드웨어 장애)에는 데이터 유실 가능 — 완전한 fsync는
     * 검색 로그의 비용 대비 효과가 낮아 적용하지 않는다.
     */
    private final boolean syncOnAppend;

    /** 세그먼트 파일명의 고유성을 보장하는 순번 카운터. */
    private final AtomicLong segmentCounter = new AtomicLong(0);

    // ── 메트릭 ──
    private final AtomicLong walBytesWritten = new AtomicLong(0);
    private final AtomicLong recoveredCount = new AtomicLong(0);

    // ── 현재 세그먼트 상태 (this 모니터로 보호) ──
    private Path currentSegmentPath;
    private BufferedWriter currentWriter;

    /**
     * 마지막 recoverAll() 호출에서 읽은 세그먼트 경로 목록.
     * DB flush 성공 후 deleteRecoveredSegments()로 삭제한다.
     */
    private List<Path> lastRecoveredSegments = List.of();

    /**
     * @param walDir       WAL 세그먼트 파일이 저장될 디렉터리 경로
     * @param syncOnAppend true이면 매 append 후 flush() 호출 (내구성 우선),
     *                     false이면 OS 버퍼링에 맡김 (처리량 우선)
     */
    public SearchLogWalManager(Path walDir, boolean syncOnAppend) {
        this.walDir = walDir;
        this.syncOnAppend = syncOnAppend;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());

        initDirectory();
        openNewSegment();
    }

    /**
     * WAL 디렉터리를 생성한다. 이미 존재하면 아무 작업도 하지 않는다.
     *
     * @throws UncheckedIOException 디렉터리 생성 실패 시 (권한 부족, 디스크 풀 등)
     */
    private void initDirectory() {
        try {
            Files.createDirectories(walDir);
        } catch (IOException e) {
            throw new UncheckedIOException("WAL 디렉터리 생성 실패: " + walDir, e);
        }
    }

    /**
     * 검색 로그 엔트리를 현재 WAL 세그먼트에 기록한다.
     *
     * <p>JSON Lines 형식으로 한 줄에 하나의 엔트리를 직렬화하여 append한다.
     * {@code syncOnAppend}가 true이면 쓰기 직후 BufferedWriter를 flush하여
     * OS 파일 버퍼에 즉시 반영한다.</p>
     *
     * <p>WAL 기록 실패 시 해당 엔트리는 인메모리 버퍼에만 존재하게 된다.
     * 이 경우 프로세스 크래시 시 해당 엔트리가 유실될 수 있지만,
     * 정상 운영 중에는 인메모리 버퍼에서 DB로 flush되므로 영향이 없다.</p>
     *
     * @param entry 기록할 검색 로그 엔트리
     */
    public synchronized void append(SearchLogEntry entry) {
        try {
            String json = objectMapper.writeValueAsString(entry);
            currentWriter.write(json);
            currentWriter.newLine();

            // syncOnAppend: 매 쓰기 후 OS 버퍼로 flush.
            // 완전한 fsync(FileDescriptor.sync())는 검색 로그 대비 비용이 과도하므로
            // BufferedWriter.flush()로 Java → OS 버퍼 전달만 보장한다.
            if (syncOnAppend) {
                currentWriter.flush();
            }

            walBytesWritten.addAndGet(json.length() + 1);
        } catch (IOException e) {
            // WAL 기록 실패 — 인메모리 버퍼에는 여전히 추가되므로
            // 정상 운영 중에는 DB flush로 저장된다. 프로세스 크래시 시에만 유실 위험.
            log.warn("WAL 기록 실패 — 인메모리 버퍼에만 존재: keyword={}", entry.keyword(), e);
        }
    }

    /**
     * 현재 세그먼트를 닫고 새 세그먼트를 연다.
     *
     * <p>flush() 시점에 호출되어, 현재까지 기록된 엔트리를 담은 세그먼트를 봉인하고
     * 새 세그먼트로 전환한다. 반환된 세그먼트 경로는 DB flush 성공 후
     * {@link #deleteSegment(Path)}로 삭제해야 한다.</p>
     *
     * <p>세그먼트가 비어있으면(엔트리 0건) 자동으로 삭제하고 null을 반환한다.</p>
     *
     * @return 닫힌 세그먼트의 경로. 비어있는 세그먼트면 null
     */
    public synchronized Path rotateSegment() {
        Path oldSegment = currentSegmentPath;

        try {
            currentWriter.flush();
            currentWriter.close();
        } catch (IOException e) {
            log.warn("WAL 세그먼트 닫기 실패: {}", oldSegment, e);
        }

        // 빈 세그먼트는 삭제하여 디스크 낭비 방지
        try {
            if (Files.size(oldSegment) == 0) {
                Files.deleteIfExists(oldSegment);
                openNewSegment();
                return null;
            }
        } catch (IOException e) {
            log.warn("WAL 세그먼트 크기 확인 실패: {}", oldSegment, e);
        }

        openNewSegment();
        return oldSegment;
    }

    /**
     * DB에 성공적으로 flush된 세그먼트를 삭제한다.
     *
     * <p>삭제 실패 시 다음 기동에서 해당 세그먼트가 복구 대상이 되어
     * 이미 DB에 있는 엔트리가 중복 INSERT될 수 있다.
     * 검색 로그는 통계 목적이므로 소수의 중복은 허용된다.</p>
     *
     * @param segmentPath 삭제할 세그먼트 경로 (null이면 무시)
     */
    public void deleteSegment(Path segmentPath) {
        if (segmentPath == null) {
            return;
        }
        try {
            Files.deleteIfExists(segmentPath);
        } catch (IOException e) {
            log.warn("WAL 세그먼트 삭제 실패 (다음 기동 시 중복 복구 가능): {}", segmentPath, e);
        }
    }

    /**
     * WAL 디렉터리의 모든 잔존 세그먼트를 읽어 엔트리 목록으로 반환한다.
     *
     * <p>애플리케이션 기동 시 {@link SearchLogWalRecovery}에서 호출한다.
     * 이전 프로세스가 비정상 종료되어 DB에 flush되지 못한 엔트리를 복원한다.</p>
     *
     * <p>현재 활성 세그먼트(방금 열린 빈 파일)는 복구 대상에서 제외한다.
     * 파싱 실패한 라인은 건너뛰어, 부분 손상된 세그먼트에서도 최대한 복구한다.</p>
     *
     * <p>읽은 세그먼트 경로는 내부에 보관하며,
     * DB 저장 성공 후 {@link #deleteRecoveredSegments()}로 삭제한다.</p>
     *
     * @return 복구된 엔트리 목록 (잔존 세그먼트가 없으면 빈 리스트)
     */
    public List<SearchLogEntry> recoverAll() {
        List<SearchLogEntry> recovered = new ArrayList<>();

        // 현재 활성 세그먼트를 제외한 잔존 세그먼트 목록 조회.
        // 파일명 순 정렬로 시간순 복구를 보장한다 (파일명에 타임스탬프 포함).
        // currentSegmentPath를 synchronized 블록에서 읽어 IS2_INCONSISTENT_SYNC를 해소한다.
        Path activeSegment;
        synchronized (this) {
            activeSegment = currentSegmentPath;
        }

        List<Path> segments;
        try (Stream<Path> stream = Files.list(walDir)) {
            segments = stream
                    .filter(p -> {
                        Path fileName = p.getFileName();
                        return fileName != null && fileName.toString().startsWith(SEGMENT_PREFIX);
                    })
                    .filter(p -> !p.equals(activeSegment))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.error("WAL 복구 실패 — 세그먼트 목록 조회 오류: {}", walDir, e);
            return recovered;
        }

        if (segments.isEmpty()) {
            return recovered;
        }

        // 각 세그먼트를 순서대로 읽어 엔트리를 복원한다.
        // 라인 단위로 파싱하여, 부분 손상된 세그먼트에서도 정상 라인은 복구한다.
        for (Path segment : segments) {
            int segmentEntries = 0;
            int parseErrors = 0;

            try (BufferedReader reader = Files.newBufferedReader(segment, StandardCharsets.UTF_8)) {
                // PMD AssignmentInOperand 회피: readLine() 결과를 별도 변수에 먼저 할당
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }

                    try {
                        SearchLogEntry entry = objectMapper.readValue(line, SearchLogEntry.class);
                        recovered.add(entry);
                        segmentEntries++;
                    } catch (JsonProcessingException e) {
                        // 라인 파싱 실패 — 해당 엔트리만 건너뛰고 나머지는 계속 복구.
                        // 프로세스 크래시 시 마지막 라인이 불완전하게 기록될 수 있다.
                        parseErrors++;
                    }
                }
            } catch (IOException e) {
                log.warn("WAL 세그먼트 읽기 실패: {}", segment, e);
            }

            if (parseErrors > 0) {
                log.warn("WAL 세그먼트 파싱 오류 — segment={}, recovered={}, errors={}",
                        segment.getFileName(), segmentEntries, parseErrors);
            }
        }

        // 복구된 세그먼트 경로를 보관하여 DB 저장 후 일괄 삭제
        lastRecoveredSegments = segments;
        recoveredCount.set(recovered.size());

        if (!recovered.isEmpty()) {
            log.info("WAL 복구 완료 — segments={}, entries={}", segments.size(), recovered.size());
        }

        return recovered;
    }

    /**
     * recoverAll()에서 읽은 세그먼트 파일을 모두 삭제한다.
     *
     * <p>{@link SearchLogWalRecovery}에서 복구된 엔트리를 DB에 저장한 후 호출한다.
     * 복구 → DB 저장 → 세그먼트 삭제 순서를 보장하여,
     * DB 저장 전에 세그먼트가 삭제되는 것을 방지한다.</p>
     */
    public void deleteRecoveredSegments() {
        for (Path segment : lastRecoveredSegments) {
            deleteSegment(segment);
        }
        lastRecoveredSegments = List.of();
    }

    /**
     * 새 세그먼트 파일을 생성하고 현재 세그먼트로 지정한다.
     *
     * <p>파일명 형식: {@code wal-{타임스탬프}-{순번}.log}
     * 타임스탬프(밀리초)와 순번을 조합하여 동일 밀리초 내 여러 세그먼트 생성 시에도
     * 파일명 충돌을 방지한다.</p>
     */
    private void openNewSegment() {
        long seq = segmentCounter.incrementAndGet();
        String fileName = SEGMENT_PREFIX + System.currentTimeMillis() + "-" + seq + SEGMENT_SUFFIX;
        currentSegmentPath = walDir.resolve(fileName);

        try {
            currentWriter = Files.newBufferedWriter(
                    currentSegmentPath,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("WAL 세그먼트 생성 실패: " + currentSegmentPath, e);
        }
    }

    /**
     * WAL writer를 닫는다.
     *
     * <p>애플리케이션 종료 시 {@link SearchLogBatchAccumulator#destroy()}에서 호출하여
     * 현재 세그먼트의 BufferedWriter를 정상적으로 닫는다.
     * 이후 잔여 데이터는 이미 DB에 flush된 상태이므로 세그먼트 파일은
     * 빈 상태로 남거나 다음 기동 시 빈 세그먼트로 무시된다.</p>
     */
    public synchronized void close() {
        try {
            if (currentWriter != null) {
                currentWriter.flush();
                currentWriter.close();
            }
        } catch (IOException e) {
            log.warn("WAL writer 닫기 실패", e);
        }
    }

    // ── 메트릭 접근자 ──

    /** WAL에 기록된 누적 바이트 수. */
    public long getWalBytesWritten() {
        return walBytesWritten.get();
    }

    /** 마지막 복구에서 복원된 엔트리 수. */
    public long getRecoveredCount() {
        return recoveredCount.get();
    }

    /** WAL 디렉터리 경로. */
    public Path getWalDir() {
        return walDir;
    }
}
