package com.shop.domain.order.service.stock;

import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.global.exception.InsufficientStockException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V1 — 비관적 잠금(Pessimistic Lock) 전략.
 *
 * <p>{@code SELECT ... FOR UPDATE}로 대상 행을 잠근 뒤 재고를 차감한다.
 * 트랜잭션이 커밋될 때까지 다른 트랜잭션은 동일 행에 대한 읽기/쓰기가 차단된다.</p>
 *
 * <h3>장점</h3>
 * <ul>
 *   <li>경합이 높아도 재시도 없이 한 번에 성공 (대기 후 진행)</li>
 *   <li>구현이 단순하고 정합성 보장이 확실함</li>
 * </ul>
 *
 * <h3>단점</h3>
 * <ul>
 *   <li>잠금 대기 시간이 레이턴시에 직접 반영됨</li>
 *   <li>잠금 순서를 잘못 설정하면 데드락 위험</li>
 *   <li>경합이 낮을 때도 FOR UPDATE 오버헤드가 존재</li>
 * </ul>
 *
 * <p>현재 프로덕션({@code OrderStockProcessor})에서 사용 중인 전략과 동일하다.</p>
 */
@Component
public class V1PessimisticLockStockDeduction implements StockDeductionStrategy {

    private final ProductRepository productRepository;

    public V1PessimisticLockStockDeduction(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    @Transactional
    public List<DeductionResult> deductStock(List<DeductionRequest> items) {
        List<Long> productIds = items.stream().map(DeductionRequest::productId).toList();

        // SELECT ... FOR UPDATE — 행 잠금 획득. productId 순 정렬로 데드락 방지.
        List<Product> lockedProducts = productRepository.findAllByIdInWithLock(productIds);
        Map<Long, Product> productMap = new LinkedHashMap<>(lockedProducts.size());
        for (Product product : lockedProducts) {
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

        return results;
    }

    @Override
    public String strategyName() {
        return "V1-Pessimistic";
    }
}
