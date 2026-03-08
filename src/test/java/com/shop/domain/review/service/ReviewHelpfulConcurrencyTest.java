package com.shop.domain.review.service;

import com.shop.domain.review.service.ReviewService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 리뷰 "도움이 돼요" 동시성 테스트
 *
 * 검증 항목:
 * 1) N명의 서로 다른 사용자가 동시에 클릭 → helpful_count == N, review_helpfuls 레코드 == N
 * 2) 같은 사용자가 동시에 여러 번 클릭 → helpful_count는 0 또는 1 (2 이상 불가)
 *
 * 주의: 실제 PostgreSQL DB에 연결하여 테스트합니다.
 *       테스트 전후로 테스트 데이터를 정리합니다.
 *
 * [BUG FIX] review_helpfuls 테이블에 fk_helpful_user FK 제약이 존재하므로,
 * 테스트에서 사용하는 모든 userId가 users 테이블에 실재해야 한다.
 * ON CONFLICT DO NOTHING은 UNIQUE 제약 충돌만 무시하며,
 * FK 위반은 DataIntegrityViolationException으로 전파된다.
 * setUp()에서 ensureTestUserExists()로 테스트용 사용자를 미리 생성한다.
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=50",
        "logging.level.org.hibernate.SQL=WARN"
})
@SuppressWarnings("PMD.CloseResource")
class ReviewHelpfulConcurrencyTest {

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 테스트 대상 리뷰 ID — DB에서 동적으로 조회
    private Long testReviewId;

    // 리뷰 작성자 ID — 셀프 투표 방지를 위해 제외
    private Long reviewAuthorId;

    /**
     * 테스트용 BCrypt 해시 (test-seed.sql과 동일한 값 재사용).
     * 동시성 테스트에서는 실제 로그인을 수행하지 않으므로
     * 유효한 BCrypt 형식이기만 하면 된다.
     */
    private static final String BCRYPT_HASH =
            "$2b$10$yand.RRKtoh8L4rsWJv4xeM6T8771FwNQpJrpQpI6LIEdhvgNlGQy";

    /** 테스트 1에서 사용할 시작 userId (reviewAuthorId 회피 후 동적 결정). */
    private long test1StartUserId;

    @BeforeEach
    void setUp() {
        // JdbcTemplate으로 1건만 조회 (findAll() OOM 방지)
        testReviewId = jdbcTemplate.queryForObject(
                "SELECT review_id FROM reviews WHERE helpful_count = 0 LIMIT 1",
                Long.class);
        reviewAuthorId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM reviews WHERE review_id = ?",
                Long.class, testReviewId);

        // 이전 테스트 데이터 정리
        cleanUp();

        // ── [BUG FIX] 테스트용 사용자 사전 생성 ──
        // review_helpfuls.user_id → users.user_id FK 제약을 충족시키기 위해
        // 각 테스트에서 사용할 모든 userId에 대응하는 users 레코드를 미리 삽입한다.

        // 테스트 1용 사용자 범위 결정 (리뷰 작성자 회피)
        int threadCount = 100;
        test1StartUserId = (reviewAuthorId >= 1 && reviewAuthorId <= threadCount)
                ? threadCount + 1
                : 1;
        for (int i = 0; i < threadCount; i++) {
            ensureTestUserExists(test1StartUserId + i);
        }

        // 테스트 2용: 같은 사용자 동시 클릭 (userId 999999)
        long sameUserId = (reviewAuthorId == 999999L) ? 999998L : 999999L;
        ensureTestUserExists(sameUserId);

        // 테스트 3용: insert 충돌 경로 재현 (userId 888888)
        long conflictUserId = (reviewAuthorId == 888888L) ? 888887L : 888888L;
        ensureTestUserExists(conflictUserId);

        System.out.println("========================================");
        System.out.println("테스트 리뷰 ID: " + testReviewId);
        System.out.println("리뷰 작성자 ID: " + reviewAuthorId + " (테스트에서 제외)");
        System.out.println("========================================");
    }

    /**
     * 지정한 userId로 테스트용 사용자를 생성한다.
     *
     * review_helpfuls 테이블의 FK 제약(fk_helpful_user)을 만족시키기 위한 용도이며,
     * 실제 로그인이나 인증에는 사용하지 않는다.
     * ON CONFLICT (user_id) DO NOTHING으로 이미 존재하는 사용자는 건너뛴다.
     * username/email에도 UNIQUE 제약이 있으므로 userId 기반으로 고유값을 생성한다.
     */
    private void ensureTestUserExists(long userId) {
        jdbcTemplate.update(
                "INSERT INTO users (user_id, username, email, password_hash, name, phone, " +
                "role, tier_id, total_spent, point_balance, is_active, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'ROLE_USER', 1, 0, 0, TRUE, NOW(), NOW()) " +
                "ON CONFLICT (user_id) DO NOTHING",
                userId,
                "helpful_test_u" + userId,               // username (UNIQUE)
                "helpful_test_" + userId + "@test.com",  // email (UNIQUE)
                BCRYPT_HASH,
                "테스트사용자" + userId,
                "010-0000-0000"
        );
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    /**
     * 테스트 1: 100명의 서로 다른 사용자가 동시에 같은 리뷰에 "도움이 돼요" 클릭
     *
     * 기대 결과:
     * - review_helpfuls 테이블에 정확히 100개 레코드
     * - reviews.helpful_count == 100
     * - 두 값이 정확히 일치 (데이터 정합성)
     */
    @Test
    @Order(1)
    @DisplayName("100명 동시 클릭 → helpful_count와 review_helpfuls 레코드 수 일치")
    void concurrentHelpful_differentUsers() throws InterruptedException {
        // Given
        int threadCount = 100;
        int poolSize = 50;

        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        CountDownLatch ready = new CountDownLatch(threadCount);  // 전원 준비 대기
        CountDownLatch start = new CountDownLatch(1);            // 동시 출발 신호
        CountDownLatch done = new CountDownLatch(threadCount);   // 전원 완료 대기

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // setUp()에서 미리 생성한 사용자 범위를 사용 (리뷰 작성자 회피 완료)
        long startUserId = test1StartUserId;

        System.out.println("테스트 사용자 범위: " + startUserId + " ~ " + (startUserId + threadCount - 1));

        // When: 100명 동시 클릭
        for (int i = 0; i < threadCount; i++) {
            final long userId = startUserId + i;
            executor.submit(() -> {
                ready.countDown();          // 준비 완료 신호
                try {
                    start.await();          // 전원 준비될 때까지 대기
                    reviewService.markHelpful(testReviewId, userId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    System.err.println("userId=" + userId + " 실패: " + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }

        try {
            ready.await(10, TimeUnit.SECONDS);  // 전원 준비 완료 대기
            start.countDown();                   // 동시 출발!
            done.await(60, TimeUnit.SECONDS);    // 전원 완료 대기 (최대 60초)
        } finally {
            executor.close();
        }

        // Then: DB에서 직접 조회하여 검증 (Hibernate 캐시 우회)
        Integer actualHelpfulCount = jdbcTemplate.queryForObject(
                "SELECT helpful_count FROM reviews WHERE review_id = ?",
                Integer.class, testReviewId);

        Integer actualRecordCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM review_helpfuls WHERE review_id = ?",
                Integer.class, testReviewId);

        System.out.println("========================================");
        System.out.println("결과:");
        System.out.println("  성공 요청 수:           " + successCount.get());
        System.out.println("  실패 요청 수:           " + failCount.get());
        System.out.println("  helpful_count (reviews): " + actualHelpfulCount);
        System.out.println("  레코드 수 (helpfuls):    " + actualRecordCount);
        System.out.println("========================================");

        // 핵심 검증: helpful_count == review_helpfuls 레코드 수
        assertThat(actualHelpfulCount)
                .as("helpful_count와 review_helpfuls 레코드 수가 일치해야 합니다")
                .isEqualTo(actualRecordCount);

        // 성공 횟수와도 일치
        assertThat(actualRecordCount)
                .as("성공한 요청 수만큼 레코드가 존재해야 합니다")
                .isEqualTo(successCount.get());

        // 전원 성공했어야 함
        assertThat(successCount.get())
                .as("100명 모두 성공해야 합니다")
                .isEqualTo(threadCount);
    }

    /**
     * 테스트 2: 같은 사용자가 동시에 10번 클릭
     *
     * markHelpful()이 토글 방식이므로:
     * - 동시에 실행되면 일부는 INSERT, 일부는 DELETE를 시도
     * - 최종 결과는 0 또는 1이어야 함 (2 이상은 불가)
     * - helpful_count == review_helpfuls 레코드 수 (정합성)
     */
    @Test
    @Order(2)
    @DisplayName("같은 사용자 동시 10회 클릭 → helpful_count는 0 또는 1")
    void concurrentHelpful_sameUser() throws InterruptedException {
        // Given
        int attemptCount = 10;
        long testUserId = (reviewAuthorId == 999999L) ? 999998L : 999999L;

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch ready = new CountDownLatch(attemptCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attemptCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        System.out.println("테스트 사용자 ID: " + testUserId + " (동일 사용자 " + attemptCount + "회 클릭)");

        // When: 같은 사용자가 동시에 10번 클릭
        for (int i = 0; i < attemptCount; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    reviewService.markHelpful(testReviewId, testUserId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        try {
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            done.await(30, TimeUnit.SECONDS);
        } finally {
            executor.close();
        }

        // Then
        Integer actualHelpfulCount = jdbcTemplate.queryForObject(
                "SELECT helpful_count FROM reviews WHERE review_id = ?",
                Integer.class, testReviewId);

        Integer actualRecordCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM review_helpfuls WHERE review_id = ?",
                Integer.class, testReviewId);

        System.out.println("========================================");
        System.out.println("결과:");
        System.out.println("  성공: " + successCount.get() + ", 실패: " + failCount.get());
        System.out.println("  helpful_count:  " + actualHelpfulCount);
        System.out.println("  레코드 수:       " + actualRecordCount);
        System.out.println("========================================");

        // helpful_count는 0 또는 1만 가능 (2 이상이면 동시성 버그)
        assertThat(actualHelpfulCount)
                .as("같은 사용자의 동시 클릭 결과는 0 또는 1이어야 합니다")
                .isBetween(0, 1);

        // 정합성: helpful_count == review_helpfuls 레코드 수
        assertThat(actualHelpfulCount)
                .as("helpful_count와 review_helpfuls 레코드 수가 일치해야 합니다")
                .isEqualTo(actualRecordCount);
    }


    /**
     * 테스트 3: inserted=0 경로 재현
     *
     * 시작 상태가 OFF(레코드 없음)인 상황에서 같은 사용자가 동시에 2회 클릭하면
     * 한 트랜잭션은 INSERT 성공(1), 다른 트랜잭션은 INSERT 충돌(0)이 발생할 수 있다.
     * 이때 markHelpful()은 최종 ON 상태를 true로 반환해야 한다.
     */
    @Test
    @Order(3)
    @DisplayName("insert 충돌(inserted=0)에서도 최종 ON 상태면 true 반환")
    void concurrentHelpful_insertedZeroPathReturnsCurrentOnState() throws InterruptedException {
        long testUserId = (reviewAuthorId == 888888L) ? 888887L : 888888L;
        boolean reproduced = false;

        for (int attempt = 1; attempt <= 30; attempt++) {
            cleanUp();

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);

            AtomicInteger trueCount = new AtomicInteger(0);

            for (int i = 0; i < 2; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        boolean currentOn = reviewService.markHelpful(testReviewId, testUserId);
                        if (currentOn) {
                            trueCount.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                        // 이 테스트는 반환 상태 검증이 목적이며, 예외 발생 시 해당 시도는 미재현으로 간주
                    } finally {
                        done.countDown();
                    }
                });
            }

            try {
                ready.await(5, TimeUnit.SECONDS);
                start.countDown();
                done.await(10, TimeUnit.SECONDS);
            } finally {
                executor.close();
            }

            Integer actualHelpfulCount = jdbcTemplate.queryForObject(
                    "SELECT helpful_count FROM reviews WHERE review_id = ?",
                    Integer.class, testReviewId);
            Integer actualRecordCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM review_helpfuls WHERE review_id = ? AND user_id = ?",
                    Integer.class, testReviewId, testUserId);

            if (trueCount.get() == 2 && actualHelpfulCount == 1 && actualRecordCount == 1) {
                reproduced = true;
                break;
            }
        }

        assertThat(reproduced)
                .as("동시 실행 중 inserted=0 경로를 재현하고 두 요청 모두 최종 ON(true)을 반환해야 합니다")
                .isTrue();
    }

    /**
     * 테스트 데이터 정리
     * - review_helpfuls에서 테스트 리뷰 관련 레코드 삭제
     * - helpful_count를 0으로 리셋
     *
     * 테스트용 users 레코드는 삭제하지 않는다.
     * review_helpfuls에 ON DELETE CASCADE가 설정되어 있어
     * 사용자 삭제 시 helpful 레코드도 연쇄 삭제되지만,
     * 매 @BeforeEach마다 100건의 사용자를 재생성하는 비용을 피하기 위해
     * helpful 레코드만 직접 삭제하고 사용자는 재사용한다.
     */
    private void cleanUp() {
        if (testReviewId != null) {
            jdbcTemplate.update(
                    "DELETE FROM review_helpfuls WHERE review_id = ?", testReviewId);
            jdbcTemplate.update(
                    "UPDATE reviews SET helpful_count = 0 WHERE review_id = ?", testReviewId);
        }
    }
    private void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

}
