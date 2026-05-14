package com.shop.domain.order.service.stock;

import com.shop.global.exception.InsufficientStockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * V3 — Atomic CAS(Compare-And-Swap) UPDATE 전략.
 *
 * <p>단일 {@code UPDATE} 문의 {@code WHERE stock_quantity >= :quantity} 조건으로
 * 재고 확인과 차감을 원자적으로 수행한다. 엔티티를 로딩하지 않으며
 * 행 잠금(FOR UPDATE)도 사용하지 않는다.</p>
 *
 * <h3>장점</h3>
 * <ul>
 *   <li>엔티티 로딩·영속성 컨텍스트 비용 없음 — 최소한의 DB 라운드트립</li>
 *   <li>DB 레벨에서 원자적으로 처리되므로 애플리케이션 잠금이 불필요</li>
 *   <li>재시도 없이 한 번의 쿼리로 성공/실패 판정</li>
 * </ul>
 *
 * <h3>단점</h3>
 * <ul>
 *   <li>UPDATE 결과(affected rows)만으로 재고 부족을 판단하므로
 *       "현재 재고가 N인데 M을 요청했다"는 상세 메시지를 생성하려면 추가 SELECT 필요</li>
 *   <li>JPA 영속성 컨텍스트를 우회하므로 1차 캐시와 불일치 가능</li>
 *   <li>복잡한 비즈니스 로직(다건 상품 + 할인 계산 등)을 순수 SQL로 표현하기 어려움</li>
 * </ul>
 *
 * <h3>[데이터 정합성 보강 2026-05-14] UPDATE ... RETURNING으로 history 스냅샷 정확도 개선</h3>
 * <p><b>기존 문제:</b> 락 없는 별도 {@code SELECT stock_quantity}로 beforeStock을 읽고,
 * CAS UPDATE 후 {@code afterStock = beforeStock - quantity}로 계산했다.
 * SELECT와 UPDATE 사이에 다른 트랜잭션이 같은 행을 갱신하면 DB의 실제 재고는 정상이지만,
 * {@link DeductionResult}에 담기는 before/after는 "그 사이 어떤 값"을 누락한 잘못된
 * 스냅샷이 되어 ProductInventoryHistory 감사 로그가 왜곡됐다.</p>
 * <p><b>수정:</b> PostgreSQL {@code UPDATE ... RETURNING stock_quantity}로 차감 후 값을
 * 한 번의 라운드트립으로 받아온다. beforeStock은 {@code afterStock + quantity}로 역산하므로
 * 항상 일관된 스냅샷이 보장된다. 재고 부족 시에는 상품명과 현재 재고를 단일 쿼리로 조회한다.</p>
 */
@Component
public class V3CasUpdateStockDeduction implements StockDeductionStrategy {

    private static final String SQL_CAS_UPDATE = """
            UPDATE products
               SET stock_quantity = stock_quantity - ?,
                   sales_count    = sales_count + ?,
                   version        = version + 1
             WHERE product_id     = ?
               AND stock_quantity >= ?
            RETURNING stock_quantity
            """;

    private static final String SQL_FETCH_FAILURE_INFO = """
            SELECT product_name, stock_quantity
              FROM products
             WHERE product_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public V3CasUpdateStockDeduction(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public List<DeductionResult> deductStock(List<DeductionRequest> items) {
        // 데드락 방지를 위해 productId 오름차순 정렬
        List<DeductionRequest> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingLong(DeductionRequest::productId));

        List<DeductionResult> results = new ArrayList<>(sorted.size());

        for (DeductionRequest req : sorted) {
            // UPDATE ... RETURNING: 차감 후 stock_quantity를 한 번에 반환받는다.
            // affected rows = 0(= 결과 행 없음)이면 재고 부족.
            List<Integer> afterStocks = jdbcTemplate.queryForList(
                    SQL_CAS_UPDATE,
                    Integer.class,
                    req.quantity(), req.quantity(), req.productId(), req.quantity());

            if (afterStocks.isEmpty()) {
                throw buildInsufficientStockException(req);
            }

            int afterStock = afterStocks.get(0);
            int beforeStock = afterStock + req.quantity();
            results.add(new DeductionResult(req.productId(), beforeStock, afterStock));
        }

        return results;
    }

    private InsufficientStockException buildInsufficientStockException(DeductionRequest req) {
        Map<String, Object> info = jdbcTemplate.queryForMap(SQL_FETCH_FAILURE_INFO, req.productId());
        String productName = (String) info.get("product_name");
        int currentStock = ((Number) info.get("stock_quantity")).intValue();
        return new InsufficientStockException(productName, req.quantity(), currentStock);
    }

    @Override
    public String strategyName() {
        return "V3-CAS";
    }
}
