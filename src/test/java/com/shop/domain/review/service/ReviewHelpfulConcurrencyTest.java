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
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=50",
        "logging.level.org.hibernate.SQL=WARN"
})
class ReviewHelpfulConcurrencyTest {

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 테스트 대상 리뷰 ID — DB에서 동적으로 조회
    private Long testReviewId;

    // 리뷰 작성자 ID — 셀프 투표 방지를 위해 제외
    private Long reviewAuthorId;

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

        System.out.println("========================================");
        System.out.println("테스트 리뷰 ID: " + testReviewId);
        System.out.println("리뷰 작성자 ID: " + reviewAuthorId + " (테스트에서 제외)");
        System.out.println("========================================");
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

        // 리뷰 작성자를 피해 userId를 선택
        // 작성자가 1~100 범위면 101~200 사용, 아니면 1~100 사용
        long startUserId = (reviewAuthorId >= 1 && reviewAuthorId <= threadCount)
                ? threadCount + 1
                : 1;

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

        ready.await(10, TimeUnit.SECONDS);  // 전원 준비 완료 대기
        start.countDown();                   // 동시 출발!
        done.await(60, TimeUnit.SECONDS);    // 전원 완료 대기 (최대 60초)
        executor.shutdown();

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

        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

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

            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            done.await(10, TimeUnit.SECONDS);
            executor.shutdown();

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
     */
    private void cleanUp() {
        if (testReviewId != null) {
            jdbcTemplate.update(
                    "DELETE FROM review_helpfuls WHERE review_id = ?", testReviewId);
            jdbcTemplate.update(
                    "UPDATE reviews SET helpful_count = 0 WHERE review_id = ?", testReviewId);
        }
    }
}
