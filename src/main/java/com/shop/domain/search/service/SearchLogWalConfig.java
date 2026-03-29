package com.shop.domain.search.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * [Phase 20] 검색 로그 WAL(Write-Ahead Log) 빈 설정.
 *
 * <p>{@code app.search-log.wal.enabled=true}일 때만 {@link SearchLogWalManager} 빈을 생성한다.
 * WAL 비활성 시 {@link SearchLogBatchAccumulator}는 기존 인메모리 전용으로 동작한다.</p>
 *
 * <h3>설정 항목</h3>
 * <table>
 *   <tr><th>속성</th><th>기본값</th><th>설명</th></tr>
 *   <tr><td>{@code app.search-log.wal.enabled}</td><td>false</td><td>WAL 활성화 여부</td></tr>
 *   <tr><td>{@code app.search-log.wal.dir}</td><td>./data/wal/search-log</td>
 *       <td>WAL 세그먼트 파일 저장 디렉터리</td></tr>
 *   <tr><td>{@code app.search-log.wal.sync-on-append}</td><td>false</td>
 *       <td>매 append 후 flush 여부 (true: 내구성 우선, false: 처리량 우선)</td></tr>
 * </table>
 *
 * <h3>운영 가이드</h3>
 * <ul>
 *   <li>WAL 디렉터리는 애플리케이션 프로세스가 쓰기 권한을 가진 경로로 설정한다.</li>
 *   <li>{@code sync-on-append=true}는 매 검색마다 OS flush를 수행하므로
 *       고처리량 환경에서는 false(기본값)를 권장한다. false일 때도
 *       OS 페이지 캐시에 의해 대부분의 크래시 시나리오에서 복구 가능하다.</li>
 *   <li>Docker 환경에서는 WAL 디렉터리를 영구 볼륨에 마운트해야 컨테이너 재시작 시
 *       세그먼트 파일이 보존된다.</li>
 * </ul>
 */
@Configuration
public class SearchLogWalConfig {

    /**
     * WAL 관리자 빈.
     *
     * <p>{@code app.search-log.wal.enabled=true}일 때만 생성된다.
     * {@code matchIfMissing=false}로 설정하여 명시적 활성화가 필요하다.
     * 이는 기존 환경에서 WAL 없이 동작하던 배포에 영향을 주지 않기 위함이다.</p>
     */
    @Bean
    @ConditionalOnProperty(name = "app.search-log.wal.enabled", havingValue = "true", matchIfMissing = false)
    public SearchLogWalManager searchLogWalManager(
            @Value("${app.search-log.wal.dir:./data/wal/search-log}") String walDir,
            @Value("${app.search-log.wal.sync-on-append:false}") boolean syncOnAppend) {
        return new SearchLogWalManager(Path.of(walDir), syncOnAppend);
    }
}
