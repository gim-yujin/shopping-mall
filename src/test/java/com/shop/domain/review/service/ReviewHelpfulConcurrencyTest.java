package com.shop.domain.review.service;

import com.shop.testsupport.TestDataFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
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
 * 3) insert 충돌(inserted=0) 경로에서 최종 ON 상태면 true 반환
 *
 * TestDataFactory로 격리된 사용자를 생성하여 시드 데이터 의존을 제거.
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=20",
        "logging.level.org.hibernate.SQL=WARN"
})
@SuppressWarnings("PMD.CloseResource")
class ReviewHelpfulConcurrencyTest {

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestDataFactory testDataFactory;

    private TestDataFactory.FixtureContext fixture;

    // 테스트 대상 리뷰 ID — DB에서 동적으로 조회
    private Long testReviewId;

    // 리뷰 작성자 ID — 셀프 투표 방지를 위해 제외
    private Long reviewAuthorId;

    // fixture로 생성한 사용자 ID 목록
    private List<Long> testUserIds;

    @BeforeEach
    void setUp() {
        fixture = testDataFactory.newContext();

        testReviewId = jdbcTemplate.queryForObject(
                "SELECT review_id FROM reviews WHERE helpful_count = 0 LIMIT 1",
                Long.class);
        reviewAuthorId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM reviews WHERE review_id = ?",
                Long.class, testReviewId);

        cleanUp();

        // fixture로 격리된 사용자 생성 (테스트 1: 20명, 테스트 2·3: 그중 재사용)
        testUserIds = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            testUserIds.add(fixture.createActiveUser());
        }
    }

    @AfterEach
    void tearDown() {
        cleanUp();
        fixture.cleanup();
    }

    /**
     * 테스트 1: 20명의 서로 다른 사용자가 동시에 같은 리뷰에 "도움이 돼요" 클릭
     *
     * 기대 결과:
     * - review_helpfuls 테이블에 정확히 20개 레코드
     * - reviews.helpful_count == 20
     * - 두 값이 정확히 일치 (데이터 정합성)
     */
    @Test
    @Order(1)
    @DisplayName("20명 동시 클릭 → helpful_count와 review_helpfuls 레코드 수 일치")
    void concurrentHelpful_differentUsers() throws InterruptedException {
        int threadCount = testUserIds.size();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final long userId = testUserIds.get(i);
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
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
            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .as("모든 스레드가 준비 상태가 되어야 합니다")
                    .isTrue();
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS))
                    .as("지정 시간 내 모든 작업이 완료되어야 합니다")
                    .isTrue();
        } finally {
            executor.close();
        }

        Integer actualHelpfulCount = jdbcTemplate.queryForObject(
                "SELECT helpful_count FROM reviews WHERE review_id = ?",
                Integer.class, testReviewId);

        Integer actualRecordCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM review_helpfuls WHERE review_id = ?",
                Integer.class, testReviewId);

        assertThat(actualHelpfulCount)
                .as("helpful_count와 review_helpfuls 레코드 수가 일치해야 합니다")
                .isEqualTo(actualRecordCount);

        assertThat(actualRecordCount)
                .as("성공한 요청 수만큼 레코드가 존재해야 합니다")
                .isEqualTo(successCount.get());

        assertThat(successCount.get())
                .as("%d명 모두 성공해야 합니다", threadCount)
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
        int attemptCount = 10;
        // fixture에서 생성한 첫 번째 사용자 재사용
        long sameUserId = testUserIds.get(0);

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch ready = new CountDownLatch(attemptCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attemptCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < attemptCount; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    reviewService.markHelpful(testReviewId, sameUserId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        try {
            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .as("모든 스레드가 준비 상태가 되어야 합니다")
                    .isTrue();
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS))
                    .as("지정 시간 내 모든 작업이 완료되어야 합니다")
                    .isTrue();
        } finally {
            executor.close();
        }

        Integer actualHelpfulCount = jdbcTemplate.queryForObject(
                "SELECT helpful_count FROM reviews WHERE review_id = ?",
                Integer.class, testReviewId);

        Integer actualRecordCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM review_helpfuls WHERE review_id = ?",
                Integer.class, testReviewId);

        assertThat(actualHelpfulCount)
                .as("같은 사용자의 동시 클릭 결과는 0 또는 1이어야 합니다")
                .isBetween(0, 1);

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
        // fixture에서 생성한 두 번째 사용자 재사용
        long conflictUserId = testUserIds.get(1);
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
                        boolean currentOn = reviewService.markHelpful(testReviewId, conflictUserId);
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
                assertThat(ready.await(5, TimeUnit.SECONDS))
                        .as("모든 스레드가 준비 상태가 되어야 합니다")
                        .isTrue();
                start.countDown();
                assertThat(done.await(10, TimeUnit.SECONDS))
                        .as("지정 시간 내 모든 작업이 완료되어야 합니다")
                        .isTrue();
            } finally {
                executor.close();
            }

            Integer actualHelpfulCount = jdbcTemplate.queryForObject(
                    "SELECT helpful_count FROM reviews WHERE review_id = ?",
                    Integer.class, testReviewId);
            Integer actualRecordCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM review_helpfuls WHERE review_id = ? AND user_id = ?",
                    Integer.class, testReviewId, conflictUserId);

            if (trueCount.get() == 2 && actualHelpfulCount == 1 && actualRecordCount == 1) {
                reproduced = true;
                break;
            }
        }

        assertThat(reproduced)
                .as("동시 실행 중 inserted=0 경로를 재현하고 두 요청 모두 최종 ON(true)을 반환해야 합니다")
                .isTrue();
    }

    private void cleanUp() {
        if (testReviewId != null) {
            jdbcTemplate.update(
                    "DELETE FROM review_helpfuls WHERE review_id = ?", testReviewId);
            jdbcTemplate.update(
                    "UPDATE reviews SET helpful_count = 0 WHERE review_id = ?", testReviewId);
        }
    }
}
