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
 * 시나리오 1 — 같은 username으로 동시 가입 (Duplicate Username)
 *   5개 스레드가 동시에 같은 username으로 signup 호출
 *   위험: existsByUsername() 통과 후 INSERT → UNIQUE 위반 → 500 에러
 *   기대: 1명만 가입 성공, 나머지는 비즈니스 예외 처리
 *
 * 시나리오 2 — 같은 email로 동시 가입 (Duplicate Email)
 *   5개 스레드가 동시에 같은 email(다른 username)으로 signup 호출
 *   위험: existsByEmail() 통과 후 INSERT → UNIQUE 위반 → 500 에러
 *   기대: 1명만 가입 성공, 나머지는 비즈니스 예외 처리
 *
 * 시나리오 3 — 다른 사용자가 동시에 같은 이메일로 프로필 변경 (Email Race)
 *   2명의 사용자가 동시에 같은 이메일로 updateProfile 호출
 *   위험: existsByEmail() 통과 후 UPDATE → UNIQUE 위반 → 500 에러
 *   기대: 1명만 변경 성공
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

    // =========================================================================
    // 시나리오 1: 같은 username으로 동시 가입
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("시나리오 1: 같은 username 5회 동시 가입 → 1명만 성공, 에러 없이 처리")
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
        AtomicInteger duplicateFailCount = new AtomicInteger(0);
        AtomicInteger otherFailCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int attempt = i + 1;
            // 같은 username, 다른 email
            final SignupRequest request = new SignupRequest(
                    username,
                    baseEmail + attempt + "@test.com",
                    "password1234",
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
                    String msg = e.getMessage();
                    if (msg != null && msg.contains("이미 사용 중인")) {
                        duplicateFailCount.incrementAndGet();
                    } else {
                        otherFailCount.incrementAndGet();
                        errors.add("시도#" + attempt + ": " + e.getClass().getSimpleName() + " - " + msg);
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
        System.out.println("  가입 성공:          " + successCount.get() + "명");
        System.out.println("  중복 실패:          " + duplicateFailCount.get() + "명");
        System.out.println("  기타 실패:          " + otherFailCount.get() + "명");
        System.out.println("  DB 사용자 수:       " + userCount + "명 (기대: 1명)");
        if (!errors.isEmpty()) {
            System.out.println("  에러:");
            errors.forEach(e -> System.out.println("    → " + e));
        }
        System.out.println("========================================");

        // 정리
        jdbcTemplate.update("DELETE FROM users WHERE username = ?", username);

        // ① 정확히 1명만 DB에 존재
        assertThat(userCount)
                .as("같은 username으로 1명만 가입되어야 합니다 (현재: %d명)", userCount)
                .isEqualTo(1);

        // ② 기타 예외 없음 (DataIntegrityViolationException 등 500 에러 없어야 함)
        assertThat(otherFailCount.get())
                .as("500 에러가 아닌 비즈니스 예외로 처리되어야 합니다: %s", errors)
                .isEqualTo(0);

        // ③ 성공 1 + 중복 실패 4 = 총 5
        assertThat(successCount.get() + duplicateFailCount.get())
                .as("성공(1) + 중복실패(4) = 총 시도(5)여야 합니다")
                .isEqualTo(threadCount);
    }

    // =========================================================================
    // 시나리오 2: 같은 email로 동시 가입
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("시나리오 2: 같은 email 5회 동시 가입 → 1명만 성공, 에러 없이 처리")
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
        AtomicInteger duplicateFailCount = new AtomicInteger(0);
        AtomicInteger otherFailCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int attempt = i + 1;
            // 다른 username, 같은 email
            final SignupRequest request = new SignupRequest(
                    baseUsername + attempt,
                    email,
                    "password1234",
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
                    String msg = e.getMessage();
                    if (msg != null && msg.contains("이미 사용 중인")) {
                        duplicateFailCount.incrementAndGet();
                    } else {
                        otherFailCount.incrementAndGet();
                        errors.add("시도#" + attempt + ": " + e.getClass().getSimpleName() + " - " + msg);
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
        System.out.println("  가입 성공:          " + successCount.get() + "명");
        System.out.println("  중복 실패:          " + duplicateFailCount.get() + "명");
        System.out.println("  기타 실패:          " + otherFailCount.get() + "명");
        System.out.println("  DB 사용자 수:       " + userCount + "명 (기대: 1명)");
        if (!errors.isEmpty()) {
            System.out.println("  에러:");
            errors.forEach(e -> System.out.println("    → " + e));
        }
        System.out.println("========================================");

        // 정리 — 테스트에서 생성된 모든 사용자 삭제
        jdbcTemplate.update("DELETE FROM users WHERE email = ?", email);
        for (int i = 1; i <= threadCount; i++) {
            jdbcTemplate.update("DELETE FROM users WHERE username = ?", baseUsername + i);
        }

        // ① 정확히 1명만 DB에 존재
        assertThat(userCount)
                .as("같은 email로 1명만 가입되어야 합니다 (현재: %d명)", userCount)
                .isEqualTo(1);

        // ② 기타 예외 없음
        assertThat(otherFailCount.get())
                .as("500 에러가 아닌 비즈니스 예외로 처리되어야 합니다: %s", errors)
                .isEqualTo(0);
    }

    // =========================================================================
    // 시나리오 3: 다른 사용자가 동시에 같은 이메일로 프로필 변경
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("시나리오 3: 2명이 동시에 같은 이메일로 프로필 변경 → 1명만 성공")
    void emailRace_updateProfile() throws InterruptedException {
        // 테스트용 사용자 2명 생성
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

        // When: 2명이 동시에 같은 이메일로 변경
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger duplicateFailCount = new AtomicInteger(0);
        AtomicInteger otherFailCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        // User A → targetEmail
        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                userService.updateProfile(userAId, "유저A", "010-0000-0001", targetEmail);
                successCount.incrementAndGet();
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && msg.contains("이미 사용 중인")) {
                    duplicateFailCount.incrementAndGet();
                } else {
                    otherFailCount.incrementAndGet();
                    errors.add("UserA: " + e.getClass().getSimpleName() + " - " + msg);
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
                String msg = e.getMessage();
                if (msg != null && msg.contains("이미 사용 중인")) {
                    duplicateFailCount.incrementAndGet();
                } else {
                    otherFailCount.incrementAndGet();
                    errors.add("UserB: " + e.getClass().getSimpleName() + " - " + msg);
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
        System.out.println("  변경 성공: " + successCount.get() + "명");
        System.out.println("  중복 실패: " + duplicateFailCount.get() + "명");
        System.out.println("  기타 실패: " + otherFailCount.get() + "명");
        System.out.println("  target email 보유자 수: " + emailCount + "명 (기대: 1명)");
        if (!errors.isEmpty()) {
            System.out.println("  에러:");
            errors.forEach(e -> System.out.println("    → " + e));
        }
        System.out.println("========================================");

        // 정리
        jdbcTemplate.update("DELETE FROM users WHERE username IN (?, ?)", userA_name, userB_name);

        // ① target email은 1명만 보유
        assertThat(emailCount)
                .as("같은 이메일을 가진 사용자는 1명이어야 합니다 (현재: %d명)", emailCount)
                .isEqualTo(1);

        // ② 기타 예외 없음
        assertThat(otherFailCount.get())
                .as("500 에러가 아닌 비즈니스 예외로 처리되어야 합니다: %s", errors)
                .isEqualTo(0);
    }
}
