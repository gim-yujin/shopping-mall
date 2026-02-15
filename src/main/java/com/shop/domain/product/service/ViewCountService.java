package com.shop.domain.product.service;

import com.shop.domain.product.repository.ProductRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 조회수 증가를 비동기로 처리하여 Browse 경로에서 동기 쓰기 트랜잭션을 제거한다.
 *
 * 이전: 상품 상세 요청 → 동기 UPDATE(viewCount) → SELECT → 응답
 *   - 100VU에서 hot row 경합 + 쓰기 트랜잭션이 커넥션 풀 점유
 *
 * 이후: 상품 상세 요청 → SELECT(읽기 전용) → 응답, UPDATE는 별도 스레드에서 처리
 *   - 읽기/쓰기 분리로 커넥션 점유 시간 최소화
 *   - 조회수는 약간의 지연이 허용되는 지표이므로 비동기 처리에 적합
 */
@Service
public class ViewCountService {

    private final ProductRepository productRepository;

    public ViewCountService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Async("asyncExecutor")
    @Transactional
    public void incrementAsync(Long productId) {
        productRepository.incrementViewCount(productId);
    }
}
