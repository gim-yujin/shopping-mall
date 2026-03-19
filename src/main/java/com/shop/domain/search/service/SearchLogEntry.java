package com.shop.domain.search.service;

import java.time.LocalDateTime;

/**
 * [Phase 19] 검색 로그 배치 쓰기용 경량 값 객체.
 *
 * <h3>기존 문제</h3>
 * <p>검색 로그 저장 시 매번 JPA {@code SearchLog} 엔티티를 생성하여 개별 INSERT를 수행했다.
 * JPA 엔티티는 영속성 컨텍스트에 등록되고, {@code IDENTITY} 전략으로 인해
 * {@code INSERT} 즉시 실행 + {@code SELECT nextval()} 가 발생하여 배치 INSERT가 불가능했다.</p>
 *
 * <h3>해결</h3>
 * <p>JPA 엔티티 대신 불변 record로 버퍼에 저장한다.
 * 영속성 컨텍스트 등록 비용이 없고, 플러시 시점에 JDBC {@code batchUpdate()}로
 * 한 번에 INSERT하여 DB 라운드트립을 대폭 감소시킨다.</p>
 *
 * <p>{@code searchedAt}은 버퍼 추가 시점(검색 발생 시점)에 캡처하여,
 * 플러시 시점이 아닌 실제 검색 시각이 기록되도록 보장한다.</p>
 *
 * @param userId          검색 사용자 ID (비로그인 시 null)
 * @param keyword         정규화된 검색 키워드 (200자 이하)
 * @param resultCount     검색 결과 수
 * @param ipAddress       클라이언트 IP (inet 타입 호환)
 * @param userAgent       User-Agent 헤더
 * @param searchedAt      검색 발생 시각 (버퍼 추가 시점에 캡처)
 */
public record SearchLogEntry(
        Long userId,
        String keyword,
        int resultCount,
        String ipAddress,
        String userAgent,
        LocalDateTime searchedAt
) {
}
