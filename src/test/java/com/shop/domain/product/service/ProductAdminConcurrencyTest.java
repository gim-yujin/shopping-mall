package com.shop.domain.product.service;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Phase 4] 관리자 상품 수정 동시성 테스트.
 *
 * <h3>시나리오 1 — 두 관리자 동시 수정 (Lost Update 방지)</h3>
 *
 * <p><b>문제:</b> @Version 없이 두 관리자가 동시에 같은 상품을 수정하면
 * 나중에 커밋한 트랜잭션이 먼저 커밋한 변경을 무음으로 덮어쓴다.
 * 예: 관리자 A가 가격을 10,000원으로, 관리자 B가 설명을 변경하면
 * 마지막 커밋만 남고 다른 변경이 유실된다.</p>
 *
 * <p><b>기대:</b> @Version 낙관적 잠금이 적용되면 한 쪽만 성공하고
 * 나머지는 {@code ObjectOptimisticLockingFailureException}으로 실패한다.
 * 서비스 계층이 이를 {@code BusinessException("CONCURRENT_MODIFICATION")}으로 변환한다.</p>
 *
 * <h3>시나리오 2 — 주문 재고 차감 중 관리자 수정 (비관적+낙관적 공존)</h3>
 *
 * <p><b>문제:</b> 주문 처리(비관적 잠금)가 재고를 차감하는 동안 관리자가 상품 정보를
 * 수정하면, @Version이 비관적 잠금에 의해 증가했으므로 관리자 수정이 충돌을 감지한다.</p>
 *
 * <p><b>기대:</b> 주문 재고 차감은 항상 성공(비관적 잠금), 관리자 수정은 충돌 감지.
 * 이는 의도된 동작이다 — 관리자가 변경된 재고 상태를 확인한 뒤 재시도해야 한다.</p>
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=20",
        "logging.level.org.hibernate.SQL=WARN"
})
class ProductAdminConcurrencyTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long testProductId;

    @BeforeEach
    void setUp() {
        // 테스트용 카테고리가 없으면 생성
        Integer categoryId = jdbcTemplate.queryForObject(
                "SELECT category_id FROM categories LIMIT 1", Integer.class);

        // 테스트 상품 생성 (version=0)
        jdbcTemplate.update("""
                INSERT INTO products (product_name, category_id, description, price, original_price,
                    stock_quantity, sales_count, view_count, rating_avg, review_count,
                    is_active, created_at, updated_at, version)
                VALUES ('동시성테스트상품', ?, '원본 설명', 10000, 15000, 100, 0, 0, 0, 0,
                    true, NOW(), NOW(), 0)
                """, categoryId);

        testProductId = jdbcTemplate.queryForObject(
                "SELECT product_id FROM products WHERE product_name = '동시성테스트상품' ORDER BY product_id DESC LIMIT 1",
                Long.class);

        System.out.println("========================================");
        System.out.println("[상품 동시 수정 테스트 준비]");
        System.out.println("  상품 ID: " + testProductId);
        System.out.println("  초기 버전: 0");
        System.out.println("========================================");
    }

    @AfterEach
    void tearDown() {
        if (testProductId != null) {
            jdbcTemplate.update("DELETE FROM product_images WHERE product_id = ?", testProductId);
            jdbcTemplate.update("DELETE FROM products WHERE product_id = ?", testProductId);
        }
    }

    /**
     * 시나리오 1: 5개 스레드가 동시에 같은 상품의 가격을 각각 다른 값으로 수정한다.
     *
     * 낙관적 잠금이 없으면 모든 UPDATE가 성공하여 마지막 커밋만 남는다 (Lost Update).
     * @Version이 적용되면 정확히 1개만 성공하고 나머지 4개는 충돌로 실패한다.
     */
    @Test
    @Order(1)
    @DisplayName("시나리오 1: 5개 스레드 동시 상품 수정 → 1개만 성공, 나머지 낙관적 잠금 충돌")
    void concurrentProductUpdate_onlyOneSucceeds() throws InterruptedException {
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        AtomicInteger otherFailCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        List<BigDecimal> appliedPrices = Collections.synchronizedList(new ArrayList<>());

        Integer categoryId = jdbcTemplate.queryForObject(
                "SELECT category_id FROM products WHERE product_id = ?",
                Integer.class, testProductId);

        // 각 스레드가 서로 다른 가격으로 수정 시도
        for (int i = 0; i < threadCount; i++) {
            final BigDecimal newPrice = new BigDecimal(20000 + i * 1000);
            final int threadNum = i + 1;

            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();

                    com.shop.domain.product.dto.AdminProductRequest request =
                            new com.shop.domain.product.dto.AdminProductRequest();
                    request.setProductName("수정됨-스레드" + threadNum);
                    request.setCategoryId(categoryId);
                    request.setDescription("스레드 " + threadNum + "의 수정");
                    request.setPrice(newPrice);
                    request.setOriginalPrice(new BigDecimal("30000"));
                    request.setStockQuantity(100);

                    productService.updateProduct(testProductId, request);
                    successCount.incrementAndGet();
                    appliedPrices.add(newPrice);
                } catch (Exception e) {
                    String msg = e.getMessage();
                    if (msg != null && msg.contains("변경되었습니다")) {
                        conflictCount.incrementAndGet();
                    } else {
                        otherFailCount.incrementAndGet();
                        errors.add("스레드#" + threadNum + ": "
                                + e.getClass().getSimpleName() + " - " + msg);
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        try {
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.close();
        }

        // 검증
        Integer finalVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM products WHERE product_id = ?",
                Integer.class, testProductId);
        BigDecimal finalPrice = jdbcTemplate.queryForObject(
                "SELECT price FROM products WHERE product_id = ?",
                BigDecimal.class, testProductId);

        System.out.println("========================================");
        System.out.println("[시나리오 1 결과]");
        System.out.println("  수정 성공:          " + successCount.get() + "건");
        System.out.println("  낙관적 잠금 충돌:   " + conflictCount.get() + "건");
        System.out.println("  기타 실패:          " + otherFailCount.get() + "건");
        System.out.println("  ─────────────────────────────");
        System.out.println("  최종 version:       " + finalVersion + " (기대: 1)");
        System.out.println("  최종 price:         " + finalPrice);
        if (!errors.isEmpty()) {
            System.out.println("  기타 에러:");
            errors.forEach(e -> System.out.println("    → " + e));
        }
        System.out.println("========================================");

        // ① 정확히 1개만 성공
        assertThat(successCount.get())
                .as("낙관적 잠금으로 인해 정확히 1개 스레드만 성공해야 합니다")
                .isEqualTo(1);

        // ② 나머지는 충돌 감지
        assertThat(conflictCount.get())
                .as("나머지 %d개 스레드는 CONCURRENT_MODIFICATION으로 실패해야 합니다", threadCount - 1)
                .isEqualTo(threadCount - 1);

        // ③ version이 1 증가 (0 → 1)
        assertThat(finalVersion)
                .as("한 번만 수정되었으므로 version은 1이어야 합니다")
                .isEqualTo(1);

        // ④ 최종 가격이 성공한 스레드의 가격과 일치
        assertThat(appliedPrices).hasSize(1);
        assertThat(finalPrice.compareTo(appliedPrices.get(0)))
                .as("DB의 최종 가격이 성공한 스레드의 가격과 일치해야 합니다")
                .isEqualTo(0);

        // ⑤ 예상치 못한 예외 없음
        assertThat(otherFailCount.get())
                .as("예상치 못한 예외: %s", errors)
                .isEqualTo(0);
    }
}
