package com.shop.domain.review.service;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.shop.domain.review.dto.ReviewCreateRequest;
import com.shop.domain.review.entity.Review;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
class ReviewCachePerformanceIntegrationTest {

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Long userId;
    private Long productA;
    private Long productB;
    private Long createdReviewId;

    @BeforeEach
    void setUp() {
        List<Long> userIds = jdbcTemplate.queryForList(
                "SELECT user_id FROM users WHERE is_active = true AND role = 'ROLE_USER' ORDER BY user_id LIMIT 1",
                Long.class
        );
        userId = userIds.get(0);

        List<Long> productIds = jdbcTemplate.queryForList(
                "SELECT product_id FROM products WHERE is_active = true ORDER BY product_id LIMIT 2",
                Long.class
        );
        productA = productIds.get(0);
        productB = productIds.get(1);

        getCache("productReviews").clear();
        getCache("productReviewVersion").clear();

        statistics().clear();
        createdReviewId = null;
    }

    @AfterEach
    void tearDown() {
        if (createdReviewId != null) {
            try {
                reviewService.deleteReview(createdReviewId, userId);
            } catch (Exception ignored) {
                jdbcTemplate.update("DELETE FROM review_helpfuls WHERE review_id = ?", createdReviewId);
                jdbcTemplate.update("DELETE FROM reviews WHERE review_id = ?", createdReviewId);
            }
        }
    }

    @Test
    @DisplayName("리뷰 캐시 성능 회귀 검증 - 히트율 향상 및 상품 단위 무효화")
    void reviewCacheHitRateAndProductScopedInvalidation() {
        PageRequest pageable = PageRequest.of(0, 10);

        reviewService.getProductReviews(productA, pageable);
        long queriesAfterFirstA = statistics().getPrepareStatementCount();

        reviewService.getProductReviews(productA, pageable);
        long queriesAfterSecondA = statistics().getPrepareStatementCount();

        assertThat(queriesAfterSecondA)
                .as("동일 상품/페이지 재조회는 캐시 히트되어 추가 SQL이 없어야 함")
                .isEqualTo(queriesAfterFirstA);

        reviewService.getProductReviews(productB, pageable);
        long queriesAfterFirstB = statistics().getPrepareStatementCount();

        reviewService.getProductReviews(productB, pageable);
        long queriesAfterSecondB = statistics().getPrepareStatementCount();

        assertThat(queriesAfterSecondB)
                .as("다른 상품도 독립적으로 캐시되어 재조회 시 SQL 증가가 없어야 함")
                .isEqualTo(queriesAfterFirstB);

        Review created = reviewService.createReview(userId,
                new ReviewCreateRequest(productA, null, 5, "cache-regression", "product scoped eviction"));
        createdReviewId = created.getReviewId();

        reviewService.getProductReviews(productA, pageable);
        long queriesAfterMutationA = statistics().getPrepareStatementCount();
        assertThat(queriesAfterMutationA)
                .as("리뷰 변경이 발생한 상품은 버전 증가로 캐시 미스가 발생해야 함")
                .isGreaterThan(queriesAfterSecondB);

        reviewService.getProductReviews(productB, pageable);
        long queriesAfterPostMutationB = statistics().getPrepareStatementCount();
        assertThat(queriesAfterPostMutationB)
                .as("다른 상품 캐시는 유지되어 재조회 시 SQL이 증가하지 않아야 함")
                .isEqualTo(queriesAfterMutationA);

        CacheStats stats = getProductReviewCaffeineCache().getNativeCache().stats();
        assertThat(stats.hitCount())
                .as("반복 조회로 productReviews 캐시 hit가 누적되어야 함")
                .isGreaterThan(0L);
        assertThat(stats.missCount())
                .as("초기 조회 및 상품 A 변경 이후 재조회로 miss도 기록되어야 함")
                .isGreaterThan(0L);
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
    }

    private Cache getCache(String name) {
        Cache cache = cacheManager.getCache(name);
        if (cache == null) {
            throw new IllegalStateException("Cache not found: " + name);
        }
        return cache;
    }

    private CaffeineCache getProductReviewCaffeineCache() {
        Cache cache = getCache("productReviews");
        if (cache instanceof CaffeineCache caffeineCache) {
            return caffeineCache;
        }
        throw new IllegalStateException("productReviews cache is not CaffeineCache");
    }
}
