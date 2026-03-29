package com.shop.domain.search.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * [Phase 20] 애플리케이션 기동 시 WAL 세그먼트에서 미처리 검색 로그를 복구한다.
 *
 * <h3>문제</h3>
 * <p>프로세스가 비정상 종료(kill -9, OOM Killer)되면 {@link SearchLogBatchAccumulator}의
 * 인메모리 버퍼에 있던 검색 로그가 유실된다. {@code DisposableBean.destroy()}는
 * 정상 종료(SIGTERM)에서만 호출되므로, 비정상 종료 시에는 실행되지 않는다.</p>
 *
 * <h3>해결</h3>
 * <p>{@link SearchLogWalManager}가 디스크에 기록한 WAL 세그먼트 파일을 읽어
 * 직접 DB에 배치 INSERT한다. WAL 세그먼트는 이전 프로세스의 마지막 flush 이후에
 * 추가된 엔트리를 담고 있으므로, 이를 복원하면 비정상 종료 시의 데이터 유실을
 * 최소화할 수 있다.</p>
 *
 * <h3>복구 흐름</h3>
 * <pre>
 * 1. walManager.recoverAll() — 잔존 세그먼트를 모두 읽어 엔트리 목록 반환
 * 2. batchWriter.writeBatch() — 엔트리를 배치 단위로 DB에 직접 INSERT
 * 3. walManager.deleteRecoveredSegments() — 복구 완료된 세그먼트 삭제
 * </pre>
 *
 * <h3>인메모리 버퍼가 아닌 DB 직접 저장을 사용하는 이유</h3>
 * <p>복구된 엔트리를 인메모리 버퍼에 추가하면, 다음 flush까지 최대 5초 동안
 * 다시 인메모리에만 존재하게 된다. 이 시간 동안 또 크래시가 발생하면
 * 복구 의미가 없어진다. DB에 직접 저장하여 즉시 영속화한다.</p>
 *
 * <h3>중복 가능성</h3>
 * <p>크래시 타이밍에 따라 이미 DB에 저장된 엔트리가 WAL에도 남아 있을 수 있다.
 * (DB flush 성공 → 세그먼트 삭제 전 크래시) 이 경우 동일 엔트리가 중복 INSERT되지만,
 * 검색 로그는 인기 검색어 통계 목적이므로 소수의 중복은 통계 정확도에 무시할 수준이다.</p>
 *
 * <h3>조건부 활성화</h3>
 * <p>{@code @ConditionalOnBean(SearchLogWalManager.class)}로,
 * WAL이 비활성화된 환경(설정 미지정)에서는 이 컴포넌트 자체가 생성되지 않는다.</p>
 *
 * @see SearchLogWalManager#recoverAll() 잔존 세그먼트 읽기
 * @see SearchLogBatchWriter#writeBatch(List) JDBC 배치 INSERT
 */
@Component
@ConditionalOnBean(SearchLogWalManager.class)
public class SearchLogWalRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SearchLogWalRecovery.class);

    /** 복구 엔트리를 DB에 저장할 때 사용하는 배치 크기. */
    private static final int RECOVERY_BATCH_SIZE = 500;

    private final SearchLogWalManager walManager;
    private final SearchLogBatchWriter batchWriter;

    public SearchLogWalRecovery(SearchLogWalManager walManager, SearchLogBatchWriter batchWriter) {
        this.walManager = walManager;
        this.batchWriter = batchWriter;
    }

    /**
     * 애플리케이션 기동 시 WAL 복구를 수행한다.
     *
     * <p>{@link ApplicationRunner}로 등록되어 Spring 컨텍스트 초기화 완료 후,
     * HTTP 요청 수신 전에 실행된다. 이 시점에 DataSource와 트랜잭션 매니저가
     * 준비되어 있으므로 DB 쓰기가 가능하다.</p>
     *
     * <p>배치 단위로 DB에 저장하며, 개별 배치 실패 시 해당 배치만 폐기하고
     * 나머지 배치는 계속 처리한다. 전체 복구가 완료(또는 부분 완료)된 후
     * 세그먼트 파일을 삭제한다.</p>
     */
    @Override
    public void run(ApplicationArguments args) {
        List<SearchLogEntry> recovered = walManager.recoverAll();
        if (recovered.isEmpty()) {
            return;
        }

        int totalSaved = 0;
        int totalDropped = 0;

        // 배치 단위로 분할하여 DB에 저장.
        // 각 배치는 독립 트랜잭션(REQUIRES_NEW)으로 실행되므로,
        // 한 배치 실패가 다른 배치에 영향을 주지 않는다.
        List<List<SearchLogEntry>> batches = partition(recovered, RECOVERY_BATCH_SIZE);
        for (List<SearchLogEntry> batch : batches) {
            try {
                batchWriter.writeBatch(batch);
                totalSaved += batch.size();
            } catch (Exception e) {
                // 배치 저장 실패 — 해당 배치의 엔트리는 유실된다.
                // 모든 세그먼트를 삭제하지 않고 계속 진행하여 나머지 배치를 최대한 복구한다.
                totalDropped += batch.size();
                log.warn("WAL 복구 배치 저장 실패 — batchSize={}, dropped={}",
                        batch.size(), totalDropped, e);
            }
        }

        // 모든 배치 처리 완료 후 세그먼트 삭제.
        // 일부 배치가 실패하더라도 세그먼트를 삭제한다 — 재시도 시 중복만 증가하고
        // 실패 원인(DB 장애 등)이 해소되지 않으면 무한 복구 루프에 빠질 수 있기 때문이다.
        walManager.deleteRecoveredSegments();

        log.info("WAL 복구 DB 저장 완료 — saved={}, dropped={}", totalSaved, totalDropped);
    }

    /**
     * 리스트를 지정된 크기의 서브리스트로 분할한다.
     *
     * @param list      분할할 리스트
     * @param batchSize 각 서브리스트의 최대 크기
     * @return 서브리스트의 리스트
     */
    static <T> List<List<T>> partition(List<T> list, int batchSize) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += batchSize) {
            partitions.add(list.subList(i, Math.min(i + batchSize, list.size())));
        }
        return partitions;
    }
}
