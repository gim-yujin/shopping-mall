package com.shop.domain.order.service.stock;

import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.global.exception.InsufficientStockException;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V2 — 낙관적 잠금(Optimistic Lock) + 재시도 전략.
 *
 * <p>행 잠금 없이 상품을 조회한 뒤 재고를 차감하고, 커밋 시 {@code @Version} 필드로
 * 충돌을 감지한다. 충돌이 발생하면 지수 백오프(exponential backoff)와 함께 새 트랜잭션에서
 * 최신 데이터를 다시 읽어 재시도한다.</p>
 *
 * <h3>장점</h3>
 * <ul>
 *   <li>잠금 대기 없이 즉시 읽기/쓰기 가능 — 경합이 낮을 때 처리량 극대화</li>
 *   <li>DB 레벨 행 잠금을 사용하지 않아 커넥션 점유 시간이 짧음</li>
 * </ul>
 *
 * <h3>단점</h3>
 * <ul>
 *   <li>경합이 높으면 재시도 빈도 증가 → 처리량 급감, 레이턴시 급등</li>
 *   <li>최대 재시도 초과 시 요청 실패 (재시도 소진)</li>
 *   <li>재시도마다 새 트랜잭션이 필요하여 구현 복잡도 증가</li>
 * </ul>
 */
@Component
public class V2OptimisticRetryStockDeduction implements StockDeductionStrategy {

    private static final Logger log = LoggerFactory.getLogger(V2OptimisticRetryStockDeduction.class);

    private static final int MAX_RETRIES = 5;
    private static final long INITIAL_BACKOFF_MS = 50;

    private final ProductRepository productRepository;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;

    public V2OptimisticRetryStockDeduction(ProductRepository productRepository,
                                           EntityManager entityManager,
                                           TransactionTemplate transactionTemplate) {
        this.productRepository = productRepository;
        this.entityManager = entityManager;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 재시도 루프는 트랜잭션 바깥에서 실행한다.
     * 충돌 발생 시 이전 트랜잭션은 롤백되고, 새 트랜잭션에서 최신 상태를 다시 읽는다.
     */
    @Override
    public List<DeductionResult> deductStock(List<DeductionRequest> items) {
        int attempt = 0;

        while (true) {
            try {
                return transactionTemplate.execute(status -> doDeduct(items));
            } catch (ObjectOptimisticLockingFailureException e) {
                attempt++;
                if (attempt > MAX_RETRIES) {
                    log.warn("V2 낙관적 잠금 재시도 한도 초과 — {}회 시도 후 실패", MAX_RETRIES);
                    throw e;
                }

                long backoff = INITIAL_BACKOFF_MS * (1L << (attempt - 1));
                log.debug("V2 낙관적 잠금 충돌 — 재시도 {}/{}, 대기 {}ms", attempt, MAX_RETRIES, backoff);

                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private List<DeductionResult> doDeduct(List<DeductionRequest> items) {
        List<Long> productIds = items.stream().map(DeductionRequest::productId).toList();

        // 잠금 없이 조회 — @Version 필드가 충돌 감지 역할을 한다.
        List<Product> products = productRepository.findAllByIdInOrderByProductId(productIds);
        Map<Long, Product> productMap = new LinkedHashMap<>(products.size());
        for (Product product : products) {
            productMap.put(product.getProductId(), product);
        }

        List<DeductionResult> results = new ArrayList<>(items.size());
        for (DeductionRequest req : items) {
            Product product = productMap.get(req.productId());
            if (product.getStockQuantity() < req.quantity()) {
                throw new InsufficientStockException(
                        product.getProductName(), req.quantity(), product.getStockQuantity());
            }

            int beforeStock = product.getStockQuantity();
            product.decreaseStock(req.quantity());
            results.add(new DeductionResult(req.productId(), beforeStock, product.getStockQuantity()));
        }

        // flush로 @Version 충돌을 커밋 전에 즉시 감지한다.
        entityManager.flush();
        return results;
    }

    @Override
    public String strategyName() {
        return "V2-Optimistic";
    }
}
