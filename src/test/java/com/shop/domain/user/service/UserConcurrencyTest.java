package com.shop.domain.user.service;

import com.shop.domain.user.dto.SignupRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회원가입 동시성 테스트
 *
 * 회원가입/프로필 변경의 UNIQUE 중복은 이전 서비스(쿠폰, 장바구니)와 성격이 다르다:
 * - 쿠폰/장바구니: 금전/수량 영향 → 서비스 레이어 직렬화 필수
 * - 회원가입: 순수 유일성 검증 → UNIQUE 제약이 데이터 보호, 서비스가 DUPLICATE 비즈니스 예외로 변환
 *
 *
 * 검증 포인트:
 * ① 데이터 무결성 — DB에 정확히 1건만 존재
 * ② 에러 분류 — 모든 실패가 "중복 관련"이며 알 수 없는 에러가 없음
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=50",
        "logging.level.org.hibernate.SQL=WARN"
})
class UserConcurrencyTest {

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String TEST_PREFIX = "conctest_" + System.currentTimeMillis();

    /**
     * 중복 관련 예외인지 판별.
     * - BusinessException("이미 사용 중인"): 서비스 레이어에서 existsBy()가 잡은 경우
     * - BusinessException("DUPLICATE"): 동시 요청으로 existsBy() 통과 후 UNIQUE 위반을 서비스가 변환한 경우
     */
    private boolean isDuplicateException(Exception e) {
        String msg = e.getMessage();
        if (msg != null && msg.contains("이미 사용 중인")) {
            return true;  // 서비스 레이어에서 감지
        }
        if (e instanceof com.shop.global.exception.BusinessException be && "DUPLICATE".equals(be.getCode())) {
            return true;  // DB UNIQUE 충돌을 서비스가 DUPLICATE로 변환
        }
        return false;
    }

    // =========================================================================
    // 시나리오 1: 같은 username으로 동시 가입
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("시나리오 1: 같은 username 5회 동시 가입 → 1명만 DB에 존재, 나머지는 중복 처리")
    void duplicateUsername_signup() throws InterruptedException {
        String username = TEST_PREFIX + "_user";
        String baseEmail = TEST_PREFIX + "_u";

        System.out.println("========================================");
        System.out.println("[시나리오 1: 같은 username 동시 가입]");
        System.out.println("  username: " + username);
        System.out.println("========================================");

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger serviceCheckCount = new AtomicInteger(0);     // existsBy()에서 잡힌 중복
        AtomicInteger constraintCheckCount = new AtomicInteger(0);  // UNIQUE 위반 → 서비스가 DUPLICATE로 변환
        AtomicInteger unknownFailCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int attempt = i + 1;
            final SignupRequest request = new SignupRequest(
                    username,
                    baseEmail + attempt + "@test.com",
                    "password1234!",
                    "테스트유저" + attempt,
                    "010-0000-000" + attempt
            );
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    userService.signup(request);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    if (e instanceof com.shop.global.exception.BusinessException be && "DUPLICATE".equals(be.getCode())) {
                        constraintCheckCount.incrementAndGet();
                    } else if (e.getMessage() != null && e.getMessage().contains("이미 사용 중인")) {
                        serviceCheckCount.incrementAndGet();
                    } else {
                        unknownFailCount.incrementAndGet();
                        errors.add("시도#" + attempt + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        Integer userCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = ?",
                Integer.class, username);

        System.out.println("========================================");
        System.out.println("[테스트 결과]");
        System.out.println("  가입 성공:                " + successCount.get() + "명");
        System.out.println("  서비스 레이어 중복 감지:  " + serviceCheckCount.get() + "회 (existsBy)");
        System.out.println("  DB UNIQUE 중복 감지:      " + constraintCheckCount.get() + "회 (→ BusinessException[DUPLICATE])");
        System.out.println("  알 수 없는 실패:          " + unknownFailCount.get() + "회");
        System.out.println("  DB 사용자 수:             " + userCount + "명 (기대: 1명)");
        if (!errors.isEmpty()) {
            System.out.println("  에러:");
            errors.forEach(e -> System.out.println("    → " + e));
        }
        System.out.println("========================================");

        // 정리
        jdbcTemplate.update("DELETE FROM users WHERE username = ?", username);

        // ① 데이터 무결성: 정확히 1명만 DB에 존재
        assertThat(userCount)
                .as("같은 username으로 1명만 가입되어야 합니다 (현재: %d명)", userCount)
                .isEqualTo(1);

        // ② 알 수 없는 에러 없음
        assertThat(unknownFailCount.get())
                .as("중복 이외의 알 수 없는 에러가 없어야 합니다: %s", errors)
                .isEqualTo(0);

        // ③ 모든 시도가 성공 또는 중복 처리
        assertThat(successCount.get() + serviceCheckCount.get() + constraintCheckCount.get())
                .as("성공 + 중복감지 = 총 시도 수")
                .isEqualTo(threadCount);
    }

    // =========================================================================
    // 시나리오 2: 같은 email로 동시 가입
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("시나리오 2: 같은 email 5회 동시 가입 → 1명만 DB에 존재, 나머지는 중복 처리")
    void duplicateEmail_signup() throws InterruptedException {
        String email = TEST_PREFIX + "_dup@test.com";
        String baseUsername = TEST_PREFIX + "_email";

        System.out.println("========================================");
        System.out.println("[시나리오 2: 같은 email 동시 가입]");
        System.out.println("  email: " + email);
        System.out.println("========================================");

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger serviceCheckCount = new AtomicInteger(0);
        AtomicInteger constraintCheckCount = new AtomicInteger(0);
        AtomicInteger unknownFailCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int attempt = i + 1;
            final SignupRequest request = new SignupRequest(
                    baseUsername + attempt,
                    email,
                    "password1234!",
                    "테스트유저" + attempt,
                    "010-1111-000" + attempt
            );
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    userService.signup(request);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    if (e instanceof com.shop.global.exception.BusinessException be && "DUPLICATE".equals(be.getCode())) {
                        constraintCheckCount.incrementAndGet();
                    } else if (e.getMessage() != null && e.getMessage().contains("이미 사용 중인")) {
                        serviceCheckCount.incrementAndGet();
                    } else {
                        unknownFailCount.incrementAndGet();
                        errors.add("시도#" + attempt + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        Integer userCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?",
                Integer.class, email);

        System.out.println("========================================");
        System.out.println("[테스트 결과]");
        System.out.println("  가입 성공:                " + successCount.get() + "명");
        System.out.println("  서비스 레이어 중복 감지:  " + serviceCheckCount.get() + "회 (existsBy)");
        System.out.println("  DB UNIQUE 중복 감지:      " + constraintCheckCount.get() + "회 (→ BusinessException[DUPLICATE])");
        System.out.println("  알 수 없는 실패:          " + unknownFailCount.get() + "회");
        System.out.println("  DB 사용자 수:             " + userCount + "명 (기대: 1명)");
        if (!errors.isEmpty()) {
            System.out.println("  에러:");
            errors.forEach(e -> System.out.println("    → " + e));
        }
        System.out.println("========================================");

        // 정리
        jdbcTemplate.update("DELETE FROM users WHERE email = ?", email);
        for (int i = 1; i <= threadCount; i++) {
            jdbcTemplate.update("DELETE FROM users WHERE username = ?", baseUsername + i);
        }

        // ① 데이터 무결성: 정확히 1명만 DB에 존재
        assertThat(userCount)
                .as("같은 email로 1명만 가입되어야 합니다 (현재: %d명)", userCount)
                .isEqualTo(1);

        // ② 알 수 없는 에러 없음
        assertThat(unknownFailCount.get())
                .as("중복 이외의 알 수 없는 에러가 없어야 합니다: %s", errors)
                .isEqualTo(0);
    }

    // =========================================================================
    // 시나리오 3: 다른 사용자가 동시에 같은 이메일로 프로필 변경
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("시나리오 3: 2명이 동시에 같은 이메일로 프로필 변경 → 1명만 성공")
    void emailRace_updateProfile() throws InterruptedException {
        String targetEmail = TEST_PREFIX + "_target@test.com";

        String userA_name = TEST_PREFIX + "_profA";
        String userB_name = TEST_PREFIX + "_profB";
        String userA_email = TEST_PREFIX + "_a_orig@test.com";
        String userB_email = TEST_PREFIX + "_b_orig@test.com";

        jdbcTemplate.update(
                """
                INSERT INTO users (username, email, password_hash, name, phone, role, tier_id, is_active, created_at, updated_at)
                VALUES (?, ?, 'hash', '유저A', '010-0000-0001', 'ROLE_USER', 1, true, NOW(), NOW())
                """, userA_name, userA_email);
        jdbcTemplate.update(
                """
                INSERT INTO users (username, email, password_hash, name, phone, role, tier_id, is_active, created_at, updated_at)
                VALUES (?, ?, 'hash', '유저B', '010-0000-0002', 'ROLE_USER', 1, true, NOW(), NOW())
                """, userB_name, userB_email);

        Long userAId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM users WHERE username = ?", Long.class, userA_name);
        Long userBId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM users WHERE username = ?", Long.class, userB_name);

        System.out.println("========================================");
        System.out.println("[시나리오 3: 이메일 경합 프로필 변경]");
        System.out.println("  User A: ID=" + userAId + ", email=" + userA_email);
        System.out.println("  User B: ID=" + userBId + ", email=" + userB_email);
        System.out.println("  Target email: " + targetEmail);
        System.out.println("========================================");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger duplicateCount = new AtomicInteger(0);
        AtomicInteger unknownFailCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        // User A → targetEmail
        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                userService.updateProfile(userAId, "유저A", "010-0000-0001", targetEmail);
                successCount.incrementAndGet();
            } catch (Exception e) {
                if (isDuplicateException(e)) {
                    duplicateCount.incrementAndGet();
                } else {
                    unknownFailCount.incrementAndGet();
                    errors.add("UserA: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                }
            } finally {
                done.countDown();
            }
        });

        // User B → targetEmail
        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                userService.updateProfile(userBId, "유저B", "010-0000-0002", targetEmail);
                successCount.incrementAndGet();
            } catch (Exception e) {
                if (isDuplicateException(e)) {
                    duplicateCount.incrementAndGet();
                } else {
                    unknownFailCount.incrementAndGet();
                    errors.add("UserB: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                }
            } finally {
                done.countDown();
            }
        });

        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        Integer emailCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?",
                Integer.class, targetEmail);

        System.out.println("========================================");
        System.out.println("[테스트 결과]");
        System.out.println("  변경 성공:     " + successCount.get() + "명");
        System.out.println("  중복 감지:     " + duplicateCount.get() + "명 (→ BusinessException[DUPLICATE])");
        System.out.println("  알 수 없는 실패: " + unknownFailCount.get() + "명");
        System.out.println("  target email 보유자 수: " + emailCount + "명 (기대: 1명)");
        if (!errors.isEmpty()) {
            System.out.println("  에러:");
            errors.forEach(e -> System.out.println("    → " + e));
        }
        System.out.println("========================================");

        // 정리
        jdbcTemplate.update("DELETE FROM users WHERE username IN (?, ?)", userA_name, userB_name);

        // ① 데이터 무결성: target email은 1명만 보유
        assertThat(emailCount)
                .as("같은 이메일을 가진 사용자는 1명이어야 합니다 (현재: %d명)", emailCount)
                .isEqualTo(1);

        // ② 알 수 없는 에러 없음
        assertThat(unknownFailCount.get())
                .as("중복 이외의 알 수 없는 에러가 없어야 합니다: %s", errors)
                .isEqualTo(0);

        // ③ 성공 + 중복 = 2 (총 시도 수)
        assertThat(successCount.get() + duplicateCount.get())
                .as("성공 + 중복 = 총 시도 수(2)")
                .isEqualTo(2);
    }
}
