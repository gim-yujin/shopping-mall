package com.shop.domain.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * [Phase 18] 주문 목록 읽기 전용 모델 — CQRS 읽기 모델 분리.
 *
 * <h3>기존 문제</h3>
 * <p>주문 목록 조회 시 OrderQueryService가 {@code Page<Order>} 엔티티를 반환했다.
 * 이로 인해 다음 문제가 발생했다:</p>
 * <ul>
 *   <li><b>2-쿼리 패턴 강제</b>: 목록 페이지에 아이템 수(itemCount)를 표시하기 위해
 *       fetchOrderItems()로 전체 OrderItem 컬렉션을 2차 쿼리로 로딩했다.
 *       실제로 필요한 건 개수(COUNT)와 대표 상품명뿐인데, 전체 아이템 데이터를 메모리에 올림</li>
 *   <li><b>Lazy 프록시 함정</b>: Order.items는 FetchType.LAZY이므로, 트랜잭션 밖에서
 *       접근 시 LazyInitializationException 위험. fetchOrderItems()가 이를 방지하지만
 *       불필요한 데이터까지 함께 로딩됨</li>
 *   <li><b>영속성 컨텍스트 오버헤드</b>: readOnly=true에서도 Order 스냅샷이 보관되어
 *       GC 부담 증가</li>
 * </ul>
 *
 * <h3>해결: 읽기 전용 플랫 프로젝션</h3>
 * <p>v_order_list 뷰를 활용한 네이티브 쿼리로 필요한 컬럼만 SELECT하고,
 * 아이템 수와 대표 상품명을 서브쿼리로 한 번에 가져와 2-쿼리 패턴을 제거한다.
 * JPA 프록시/스냅샷 없이 불변 record에 직접 매핑된다.</p>
 *
 * <h3>Thymeleaf 호환성</h3>
 * <p>Java record의 컴포넌트 접근자는 Spring Boot 3.x의 Thymeleaf에서
 * 프로퍼티처럼 접근 가능하다 ({@code ${order.orderNumber}}).</p>
 */
public record OrderListReadModel(
        Long orderId,
        String orderNumber,
        Long userId,
        String orderStatus,
        BigDecimal totalAmount,
        BigDecimal discountAmount,
        BigDecimal shippingFee,
        BigDecimal finalAmount,
        LocalDateTime orderDate,
        LocalDateTime paidAt,
        LocalDateTime shippedAt,
        LocalDateTime deliveredAt,
        LocalDateTime cancelledAt,
        int itemCount,
        String firstProductName
) {
    /**
     * 네이티브 SQL 결과(Object[])로부터 읽기 모델을 생성한다.
     *
     * <p>네이티브 쿼리는 java.sql.Timestamp를 반환하므로 LocalDateTime으로 변환한다.
     * JPQL은 Hibernate가 자동 변환하지만, 네이티브 쿼리는 JDBC 드라이버 타입 그대로 전달된다.</p>
     *
     * @param columns v_order_list 뷰의 컬럼 순서와 일치하는 배열
     */
    public static OrderListReadModel fromNativeRow(Object[] columns) {
        Long orderId = ((Number) columns[0]).longValue();
        String orderNumber = (String) columns[1];
        Long userId = columns[2] != null ? ((Number) columns[2]).longValue() : null;
        String orderStatus = (String) columns[3];
        BigDecimal totalAmount = (BigDecimal) columns[4];
        BigDecimal discountAmount = (BigDecimal) columns[5];
        BigDecimal shippingFee = (BigDecimal) columns[6];
        BigDecimal finalAmount = (BigDecimal) columns[7];
        LocalDateTime orderDate = toLocalDateTime(columns[8]);
        LocalDateTime paidAt = toLocalDateTime(columns[9]);
        LocalDateTime shippedAt = toLocalDateTime(columns[10]);
        LocalDateTime deliveredAt = toLocalDateTime(columns[11]);
        LocalDateTime cancelledAt = toLocalDateTime(columns[12]);
        int itemCount = columns[13] != null ? ((Number) columns[13]).intValue() : 0;
        String firstProductName = (String) columns[14];

        return new OrderListReadModel(
                orderId, orderNumber, userId, orderStatus,
                totalAmount, discountAmount, shippingFee, finalAmount,
                orderDate, paidAt, shippedAt, deliveredAt, cancelledAt,
                itemCount, firstProductName
        );
    }

    /**
     * [Phase 18] 네이티브 SQL의 java.sql.Timestamp → LocalDateTime 안전 변환.
     * null 컬럼은 null로 반환한다.
     */
    private static LocalDateTime toLocalDateTime(Object column) {
        if (column == null) {
            return null;
        }
        return ((java.sql.Timestamp) column).toLocalDateTime();
    }
}
