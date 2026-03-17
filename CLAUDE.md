# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Spring Boot 3.4.1 e-commerce monolith (Java 21, Gradle 9.3.1, PostgreSQL 14.x). Hybrid rendering: Thymeleaf SSR for web UI + REST API at `/api/**`. Korean-language README is the SSOT; detailed docs live in `docs/`.

## Build & Run Commands

```bash
./gradlew bootRun                        # Start app at http://localhost:8080
./gradlew test                           # Run all tests (also generates JaCoCo report)
./gradlew test --tests 'com.shop.domain.order.service.OrderServiceTest'  # Single test class
./gradlew test --tests '*.OrderServiceTest.testMethodName'               # Single test method
./gradlew check                          # Checkstyle + PMD only (test is excluded from check)
./gradlew jacocoTestCoverageVerification # Verify 60% line coverage minimum
./gradlew spotbugsMain spotbugsTest -PenableSpotbugs=true  # SpotBugs (non-blocking, opt-in)
./scripts/check-domain-dependencies.sh   # Detect cross-domain bidirectional dependencies
./scripts/validate-doc-stats.sh          # Verify README codebase snapshot numbers match reality
```

## Test Environment

- Tests require a running PostgreSQL instance. Connection configured via env vars: `TEST_DB_URL`, `TEST_DB_USERNAME`, `TEST_DB_PASSWORD` (defaults: `localhost:5432/shopping_mall_db`, `postgres`, `4321`).
- Test DB init order: `test-reset.sql` → `schema.sql` → `test-seed.sql` (auto via `spring.sql.init.mode=always` in test profile).
- CI resets the public schema before each phase: `DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public;`
- Production uses `ddl-auto=validate` and `sql.init.mode=never` — schema must be applied manually from `src/main/resources/schema.sql` and `migration/*.sql`.

## Quality Gates

- **Checkstyle** (`config/checkstyle/checkstyle.xml`): maxWarnings=0, failOnError=true.
- **PMD** (`config/pmd/ruleset.xml`, test-specific `ruleset-test.xml`): failOnError=true.
- **SpotBugs**: Off by default; enable with `-PenableSpotbugs=true`. Non-blocking in CI.
- **JaCoCo**: 60% LINE coverage minimum. Excludes: `config/**`, `dto/**`, `ShopApplication.class`, `scheduler/**`. Report at `build/reports/jacoco/test/html/index.html`.

## Architecture

### Package Layout

`com.shop.global` — cross-cutting: security (dual filter chains), caching (Caffeine), rate limiting, idempotency, exception handling, transactional outbox, domain events.

`com.shop.domain.{user,product,category,cart,wishlist,order,coupon,point,review,search,inventory}` — each domain follows Controller → Service → Repository → Entity.

### Key Patterns

- **Dual security filter chains**: Chain 1 (`/api/**`) is stateless, CSRF disabled. Chain 2 (web forms) is session-based, CSRF enabled. Configured in `SecurityConfig`.
- **Order domain service decomposition**: `OrderService` (facade) delegates to `OrderCreationService`, `OrderCancellationService`, `PartialCancellationService`, `OrderQueryService`. Validation in `OrderInvariantValidator`.
- **Cross-domain adapter**: `UserTierOrderAdapter` bridges user tier to order domain — avoids direct cross-domain dependencies.
- **Transactional Outbox**: `OutboxEvent` entity ensures atomic event publishing alongside business writes.
- **Concurrency**: Pessimistic locking for inventory/cart/order state transitions. Lock acquisition ordering to prevent deadlocks.
- **Caching**: Caffeine with tiered TTLs (1s–30min) across 11 named caches. Stats logged at 30s intervals.

### Test Patterns

- Unit tests: `@ExtendWith(MockitoExtension.class)` + `standaloneSetup` for controllers.
- Security in unit tests: inject auth via `SecurityContextHolder` directly, clear in `@AfterEach`.
- Watch out: mock return values using `new ArrayList<>(List.of(...))` when the code mutates the list (e.g., `sort()`).
- `standaloneSetup` has no `GlobalExceptionHandler` — exceptions wrap in `ServletException`; assert with `hasCauseInstanceOf()`.
- Test fixtures: `src/test/java/com/shop/testsupport/TestDataFactory.java`.
