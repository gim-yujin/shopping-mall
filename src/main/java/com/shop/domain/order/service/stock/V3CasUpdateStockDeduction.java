package com.shop.domain.order.service.stock;

import com.shop.domain.product.repository.ProductRepository;
import com.shop.global.exception.InsufficientStockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
 */
@Component
public class V3CasUpdateStockDeduction implements StockDeductionStrategy {

    private final ProductRepository productRepository;
    private final JdbcTemplate jdbcTemplate;

    public V3CasUpdateStockDeduction(ProductRepository productRepository, JdbcTemplate jdbcTemplate) {
        this.productRepository = productRepository;
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
            // UPDATE 전에 현재 재고를 조회 (before snapshot용)
            Integer beforeStock = jdbcTemplate.queryForObject(
                    "SELECT stock_quantity FROM products WHERE product_id = ?",
                    Integer.class, req.productId());

            // CAS UPDATE: WHERE stock_quantity >= :quantity 조건으로 원자적 차감.
            // affected rows = 0이면 재고 부족.
            int affected = productRepository.decreaseStockAtomic(req.productId(), req.quantity());

            if (affected == 0) {
                String productName = jdbcTemplate.queryForObject(
                        "SELECT product_name FROM products WHERE product_id = ?",
                        String.class, req.productId());
                throw new InsufficientStockException(
                        productName, req.quantity(), beforeStock != null ? beforeStock : 0);
            }

            int afterStock = beforeStock - req.quantity();
            results.add(new DeductionResult(req.productId(), beforeStock, afterStock));
        }

        return results;
    }

    @Override
    public String strategyName() {
        return "V3-CAS";
    }
}
