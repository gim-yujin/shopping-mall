package com.shop.domain.review.service;

import com.shop.domain.product.entity.Product;
import com.shop.domain.product.service.ProductService;
import com.shop.domain.review.dto.ReviewCreateRequest;
import com.shop.domain.review.entity.Review;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * ReviewService 통합 테스트 — 리뷰 CRUD + 도움돼요 토글
 *
 * 검증 항목:
 * 1) createReview 정상: 리뷰 생성, 상품 평점 갱신
 * 2) createReview 예외: 동일 orderItem 중복 리뷰
 * 3) deleteReview 정상: 삭제 후 평점 재계산
 * 4) deleteReview 예외: 본인 리뷰가 아닌 경우, 존재하지 않는 리뷰
 * 5) markHelpful: 토글(추가/취소), 셀프 투표 방지, 카운트 정합성
 * 6) getProductReviews / getUserReviews / getHelpedReviewIds 조회
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "logging.level.org.hibernate.SQL=WARN",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
class ReviewServiceIntegrationTest {

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProductService productService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Long testUserId;
    private Long otherUserId;
    private Long testProductId;

    // 원본 상품 평점 백업
    private BigDecimal originalRatingAvg;
    private int originalReviewCount;
    private int originalActualReviewCount;

    // 테스트 중 생성된 데이터 추적
    private final List<Long> createdReviewIds = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> createdOrderIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // 활성 사용자 2명 (리뷰 작성자 + 도움돼요 클릭자)
        List<Long> userIds = jdbcTemplate.queryForList(
                "SELECT user_id FROM users WHERE is_active = true AND role = 'ROLE_USER' ORDER BY user_id LIMIT 2",
                Long.class);
        testUserId = userIds.get(0);
        otherUserId = userIds.get(1);

        // 활성 상품 1개
        testProductId = jdbcTemplate.queryForObject(
                "SELECT product_id FROM products WHERE is_active = true LIMIT 1",
                Long.class);

        // review_count를 실제 리뷰 수와 동기화 (이전 테스트/운영으로 불일치 가능)
        jdbcTemplate.update("""
                UPDATE products SET review_count = (SELECT COUNT(*) FROM reviews WHERE product_id = ?),
                       rating_avg = COALESCE((SELECT AVG(rating) FROM reviews WHERE product_id = ?), 0)
                WHERE product_id = ?
                """, testProductId, testProductId, testProductId);

        // productDetail 캐시 evict (이전 테스트의 stale 엔트리 방지)
        productService.evictProductDetailCache(testProductId);

        // 원본 평점 백업
        var state = jdbcTemplate.queryForMap(
                "SELECT rating_avg, review_count FROM products WHERE product_id = ?",
                testProductId);
        originalRatingAvg = (BigDecimal) state.get("rating_avg");
        originalReviewCount = ((Number) state.get("review_count")).intValue();
        originalActualReviewCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reviews WHERE product_id = ?",
                Integer.class, testProductId);

        System.out.println("  [setUp] 작성자: " + testUserId + ", 클릭자: " + otherUserId
                + ", 상품: " + testProductId
                + " (review_count=" + originalReviewCount
                + ", actual=" + originalActualReviewCount + ")");
    }

    @AfterEach
    void tearDown() {
        // 생성된 리뷰 관련 데이터 삭제
        for (Long reviewId : createdReviewIds) {
            jdbcTemplate.update("DELETE FROM review_helpfuls WHERE review_id = ?", reviewId);
            jdbcTemplate.update("DELETE FROM reviews WHERE review_id = ?", reviewId);
        }
        createdReviewIds.clear();

        for (Long orderId : createdOrderIds) {
            jdbcTemplate.update("DELETE FROM orders WHERE order_id = ?", orderId);
        }
        createdOrderIds.clear();

        // 상품 평점 원본 복원
        jdbcTemplate.update(
                "UPDATE products SET rating_avg = ?, review_count = ? WHERE product_id = ?",
                originalRatingAvg, originalReviewCount, testProductId);

        // productDetail 캐시 evict (복원된 DB 값과 캐시 불일치 방지)
        productService.evictProductDetailCache(testProductId);
    }

    // ==================== createReview ====================

    private Long createOrderItemForReview(Long userId, Long productId, String orderStatus) {
        String orderNumber = "TEST-REVIEW-" + UUID.randomUUID();

        Long orderId = jdbcTemplate.queryForObject(
                """
                INSERT INTO orders (
                    order_number, user_id, order_status, total_amount, discount_amount,
                    tier_discount_amount, coupon_discount_amount,
                    shipping_fee, final_amount, payment_method, shipping_address,
                    recipient_name, recipient_phone, order_date
                )
                VALUES (?, ?, ?, 10000, 0, 0, 0, 0, 10000, 'CARD', '테스트주소', '테스터', '010-0000-0000', CURRENT_TIMESTAMP)
                RETURNING order_id
                """,
                Long.class,
                orderNumber, userId, orderStatus
        );
        createdOrderIds.add(orderId);

        return jdbcTemplate.queryForObject(
                """
                INSERT INTO order_items (
                    order_id, product_id, product_name, quantity, unit_price, discount_rate, subtotal, created_at
                )
                VALUES (?, ?, ?, 1, 10000, 0, 10000, CURRENT_TIMESTAMP)
                RETURNING order_item_id
                """,
                Long.class,
                orderId,
                productId,
                "리뷰테스트상품"
        );
    }

    @Test
    @DisplayName("getReviewableOrderItems - 단일 쿼리로 조회하고 기존 기준과 동일한 결과를 반환")
    void getReviewableOrderItems_usesSingleQueryAndKeepsResultConsistency() {
        // Given: 배송완료 3건(2건은 미리뷰, 1건은 리뷰 완료), 배송중 1건
        Long unreviewedLatest = createOrderItemForReview(testUserId, testProductId, "DELIVERED");
        Long reviewed = createOrderItemForReview(testUserId, testProductId, "DELIVERED");
        Long unreviewedOldest = createOrderItemForReview(testUserId, testProductId, "DELIVERED");
        createOrderItemForReview(testUserId, testProductId, "SHIPPED");

        Review existing = reviewService.createReview(testUserId,
                new ReviewCreateRequest(testProductId, reviewed, 5, "기작성", "이미 작성된 리뷰"));
        createdReviewIds.add(existing.getReviewId());

        Statistics statistics = statistics();
        statistics.clear();

        // When
        List<com.shop.domain.order.entity.OrderItem> actual = reviewService.getReviewableOrderItems(testUserId, testProductId);
        long queryCount = statistics.getPrepareStatementCount();

        // Then: 단일 쿼리로 조회
        assertThat(queryCount)
                .as("리뷰 가능 주문 항목 조회는 DB 쿼리 1회여야 한다")
                .isEqualTo(1L);

        // Then: 기존 로직(배송완료 + 본인 + 동일 상품 + 미리뷰)과 결과 동일
        List<Long> expectedOrderItemIds = jdbcTemplate.queryForList(
                """
                SELECT oi.order_item_id
                FROM order_items oi
                JOIN orders o ON o.order_id = oi.order_id
                WHERE o.user_id = ?
                  AND oi.product_id = ?
                  AND o.order_status = 'DELIVERED'
                  AND NOT EXISTS (
                      SELECT 1 FROM reviews r
                      WHERE r.user_id = ?
                        AND r.order_item_id = oi.order_item_id
                  )
                ORDER BY o.delivered_at DESC, oi.order_item_id DESC
                """,
                Long.class,
                testUserId, testProductId, testUserId
        );

        List<Long> actualOrderItemIds = actual.stream()
                .map(com.shop.domain.order.entity.OrderItem::getOrderItemId)
                .toList();

        assertThat(actualOrderItemIds).containsExactlyElementsOf(expectedOrderItemIds);
        assertThat(actualOrderItemIds).contains(unreviewedLatest, unreviewedOldest);
        assertThat(actualOrderItemIds).doesNotContain(reviewed);
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
    }

    private ReviewCreateRequest reviewRequest(Long userId, Long productId, int rating, String title, String content) {
        Long orderItemId = createOrderItemForReview(userId, productId, "DELIVERED");
        return new ReviewCreateRequest(productId, orderItemId, rating, title, content);
    }

    @Test
    @DisplayName("createReview 성공 — 리뷰 생성 + 상품 평점 갱신")
    void createReview_success() {
        // Given
        ReviewCreateRequest request = reviewRequest(
                testUserId, testProductId, 5, "훌륭합니다", "매우 만족합니다.");

        // When
        Review review = reviewService.createReview(testUserId, request);
        createdReviewIds.add(review.getReviewId());

        // Then
        assertThat(review.getReviewId()).isNotNull();
        assertThat(review.getProductId()).isEqualTo(testProductId);
        assertThat(review.getUserId()).isEqualTo(testUserId);
        assertThat(review.getRating()).isEqualTo(5);
        assertThat(review.getTitle()).isEqualTo("훌륭합니다");
        assertThat(review.getHelpfulCount()).isEqualTo(0);

        // 상품 review_count 증가 확인
        int newReviewCount = jdbcTemplate.queryForObject(
                "SELECT review_count FROM products WHERE product_id = ?",
                Integer.class, testProductId);
        assertThat(newReviewCount).isEqualTo(originalActualReviewCount + 1);

        System.out.println("  [PASS] 리뷰 생성: reviewId=" + review.getReviewId()
                + ", reviewCount: " + originalReviewCount + " → " + newReviewCount);
    }

    @Test
    @DisplayName("createReview 직후 productDetail 캐시가 evict되어 상세 평점/리뷰수가 즉시 반영된다")
    void createReview_evictsProductDetailCacheImmediately() {
        Product before = productService.findByIdAndIncrementView(testProductId);
        int beforeReviewCount = before.getReviewCount();

        Review review = reviewService.createReview(testUserId,
                reviewRequest(testUserId, testProductId, 5, "캐시반영", "생성 직후 반영"));
        createdReviewIds.add(review.getReviewId());

        Product after = productService.findByIdAndIncrementView(testProductId);
        assertThat(after.getReviewCount()).isEqualTo(beforeReviewCount + 1);
    }

    @Test
    @DisplayName("createReview — 다른 평점으로 여러 리뷰 시 평균 변화")
    void createReview_updatesAverageRating() {
        // Given: 5점, 3점 리뷰 2개 생성
        Review r1 = reviewService.createReview(testUserId,
                reviewRequest(testUserId, testProductId, 5, "좋아요", null));
        createdReviewIds.add(r1.getReviewId());

        Review r2 = reviewService.createReview(otherUserId,
                reviewRequest(otherUserId, testProductId, 3, "보통", null));
        createdReviewIds.add(r2.getReviewId());

        // Then: 평점이 갱신됨
        BigDecimal newAvg = jdbcTemplate.queryForObject(
                "SELECT rating_avg FROM products WHERE product_id = ?",
                BigDecimal.class, testProductId);
        assertThat(newAvg).isNotNull();

        int newCount = jdbcTemplate.queryForObject(
                "SELECT review_count FROM products WHERE product_id = ?",
                Integer.class, testProductId);
        assertThat(newCount).isEqualTo(originalActualReviewCount + 2);

        System.out.println("  [PASS] 평점 갱신: " + originalRatingAvg + " → " + newAvg
                + " (리뷰 " + originalReviewCount + " → " + newCount + ")");
    }

    @Test
    @DisplayName("createReview 실패 — 동일 orderItem 중복 리뷰")
    void createReview_duplicateOrderItem_throwsException() {
        // Given: 배송 완료된 본인 주문 항목
        Long realOrderItemId = createOrderItemForReview(testUserId, testProductId, "DELIVERED");

        Review first = reviewService.createReview(testUserId,
                new ReviewCreateRequest(testProductId, realOrderItemId, 4, "첫 리뷰", null));
        createdReviewIds.add(first.getReviewId());

        // When & Then: 같은 orderItemId로 다시
        assertThatThrownBy(() -> reviewService.createReview(testUserId,
                new ReviewCreateRequest(testProductId, realOrderItemId, 5, "중복", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 리뷰를 작성");

        System.out.println("  [PASS] 동일 orderItem 중복 리뷰 → BusinessException");
    }

    @Test
    @DisplayName("createReview 성공 — 동일 user/product라도 서로 다른 주문 항목이면 각각 작성 가능")
    void createReview_sameUserAndProductWithDifferentOrderItems_success() {
        Review first = reviewService.createReview(testUserId,
                reviewRequest(testUserId, testProductId, 4, "첫 리뷰", null));
        Review second = reviewService.createReview(testUserId,
                reviewRequest(testUserId, testProductId, 5, "두번째", null));
        createdReviewIds.add(first.getReviewId());
        createdReviewIds.add(second.getReviewId());

        assertThat(first.getReviewId()).isNotEqualTo(second.getReviewId());
        assertThat(first.getOrderItemId()).isNotEqualTo(second.getOrderItemId());
    }

    @Test
    @DisplayName("createReview 실패 — 타인 주문 항목")
    void createReview_orderItemOwnedByOtherUser_throwsException() {
        Long otherUsersOrderItemId = createOrderItemForReview(otherUserId, testProductId, "DELIVERED");

        assertThatThrownBy(() -> reviewService.createReview(testUserId,
                new ReviewCreateRequest(testProductId, otherUsersOrderItemId, 5, "권한없음", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("본인 주문");
    }

    @Test
    @DisplayName("createReview 실패 — 주문 상품과 요청 상품 불일치")
    void createReview_productMismatch_throwsException() {
        Long anotherProductId = jdbcTemplate.queryForObject(
                "SELECT product_id FROM products WHERE is_active = true AND product_id <> ? ORDER BY product_id LIMIT 1",
                Long.class,
                testProductId
        );
        assertThat(anotherProductId).as("테스트용 다른 상품이 필요합니다.").isNotNull();

        Long orderItemId = createOrderItemForReview(testUserId, anotherProductId, "DELIVERED");

        assertThatThrownBy(() -> reviewService.createReview(testUserId,
                new ReviewCreateRequest(testProductId, orderItemId, 5, "상품불일치", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("일치하지 않습니다");
    }

    @Test
    @DisplayName("createReview 실패 — 미배송 주문 상태")
    void createReview_notDeliveredOrderStatus_throwsException() {
        Long orderItemId = createOrderItemForReview(testUserId, testProductId, "SHIPPED");

        assertThatThrownBy(() -> reviewService.createReview(testUserId,
                new ReviewCreateRequest(testProductId, orderItemId, 5, "미배송", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("배송 완료");
    }

    @Test
    @DisplayName("createReview 성공 — 배송 완료된 본인 주문 항목")
    void createReview_validDeliveredOrderItem_success() {
        Long orderItemId = createOrderItemForReview(testUserId, testProductId, "DELIVERED");

        Review review = reviewService.createReview(testUserId,
                new ReviewCreateRequest(testProductId, orderItemId, 5, "정상", "주문 기반 리뷰"));
        createdReviewIds.add(review.getReviewId());

        assertThat(review.getOrderItemId()).isEqualTo(orderItemId);
        assertThat(review.getProductId()).isEqualTo(testProductId);
        assertThat(review.getUserId()).isEqualTo(testUserId);
    }

    // ==================== deleteReview ====================

    @Test
    @DisplayName("deleteReview 성공 — 삭제 후 상품 평점 재계산")
    void deleteReview_success() {
        // Given: 리뷰 생성
        Long orderItemId = createOrderItemForReview(testUserId, testProductId, "DELIVERED");
        Review review = reviewService.createReview(testUserId,
                new ReviewCreateRequest(testProductId, orderItemId, 5, "삭제될 리뷰", null));
        createdReviewIds.add(review.getReviewId());

        int countAfterCreate = jdbcTemplate.queryForObject(
                "SELECT review_count FROM products WHERE product_id = ?",
                Integer.class, testProductId);

        // When
        reviewService.deleteReview(review.getReviewId(), testUserId);
        createdReviewIds.remove(review.getReviewId()); // 이미 삭제됨

        // Then: 리뷰 삭제 확인
        int exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reviews WHERE review_id = ?",
                Integer.class, review.getReviewId());
        assertThat(exists).isZero();

        // review_count 감소 확인
        int countAfterDelete = jdbcTemplate.queryForObject(
                "SELECT review_count FROM products WHERE product_id = ?",
                Integer.class, testProductId);
        assertThat(countAfterDelete).isEqualTo(countAfterCreate - 1);

        System.out.println("  [PASS] 리뷰 삭제: reviewCount " + countAfterCreate + " → " + countAfterDelete);
    }

    @Test
    @DisplayName("deleteReview 직후 productDetail 캐시가 evict되어 상세 평점/리뷰수가 즉시 반영된다")
    void deleteReview_evictsProductDetailCacheImmediately() {
        Review review = reviewService.createReview(testUserId,
                reviewRequest(testUserId, testProductId, 5, "삭제캐시", "삭제 직후 반영"));
        createdReviewIds.add(review.getReviewId());

        Product cachedAfterCreate = productService.findByIdAndIncrementView(testProductId);
        int countAfterCreate = cachedAfterCreate.getReviewCount();

        reviewService.deleteReview(review.getReviewId(), testUserId);
        createdReviewIds.remove(review.getReviewId());

        Product afterDelete = productService.findByIdAndIncrementView(testProductId);
        assertThat(afterDelete.getReviewCount()).isEqualTo(countAfterCreate - 1);
    }

    @Test
    @DisplayName("deleteReview 실패 — 본인 리뷰가 아닌 경우")
    void deleteReview_notOwner_throwsException() {
        // Given
        Review review = reviewService.createReview(testUserId,
                reviewRequest(testUserId, testProductId, 4, "테스트", null));
        createdReviewIds.add(review.getReviewId());

        // When & Then: 다른 사용자가 삭제 시도
        assertThatThrownBy(() -> reviewService.deleteReview(review.getReviewId(), otherUserId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("본인의 리뷰만 삭제");

        System.out.println("  [PASS] 타인 리뷰 삭제 → BusinessException");
    }

    @Test
    @DisplayName("deleteReview 실패 — 존재하지 않는 리뷰")
    void deleteReview_notFound_throwsException() {
        Long maxId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(review_id), 0) FROM reviews", Long.class);

        assertThatThrownBy(() -> reviewService.deleteReview(maxId + 9999, testUserId))
                .isInstanceOf(ResourceNotFoundException.class);

        System.out.println("  [PASS] 존재하지 않는 리뷰 삭제 → ResourceNotFoundException");
    }

    // ==================== markHelpful ====================

    @Test
    @DisplayName("markHelpful — 토글: 추가 → 취소 → 추가")
    void markHelpful_toggle() {
        // Given: 리뷰 생성 (testUserId 작성)
        Review review = reviewService.createReview(testUserId,
                reviewRequest(testUserId, testProductId, 4, "도움돼요 테스트", null));
        createdReviewIds.add(review.getReviewId());

        // When: otherUserId가 도움돼요 (추가)
        boolean result1 = reviewService.markHelpful(review.getReviewId(), otherUserId);
        assertThat(result1).isTrue();

        int count1 = jdbcTemplate.queryForObject(
                "SELECT helpful_count FROM reviews WHERE review_id = ?",
                Integer.class, review.getReviewId());
        assertThat(count1).isEqualTo(1);

        // When: 다시 클릭 (취소)
        boolean result2 = reviewService.markHelpful(review.getReviewId(), otherUserId);
        assertThat(result2).isFalse();

        int count2 = jdbcTemplate.queryForObject(
                "SELECT helpful_count FROM reviews WHERE review_id = ?",
                Integer.class, review.getReviewId());
        assertThat(count2).isEqualTo(0);

        // When: 또 클릭 (추가)
        boolean result3 = reviewService.markHelpful(review.getReviewId(), otherUserId);
        assertThat(result3).isTrue();

        int count3 = jdbcTemplate.queryForObject(
                "SELECT helpful_count FROM reviews WHERE review_id = ?",
                Integer.class, review.getReviewId());
        assertThat(count3).isEqualTo(1);

        System.out.println("  [PASS] 도움돼요 토글: 추가(1) → 취소(0) → 추가(1)");
    }

    @Test
    @DisplayName("markHelpful — 셀프 투표 방지")
    void markHelpful_selfVote_throwsException() {
        // Given
        Review review = reviewService.createReview(testUserId,
                reviewRequest(testUserId, testProductId, 4, "셀프 테스트", null));
        createdReviewIds.add(review.getReviewId());

        // When & Then: 본인이 도움돼요 클릭
        assertThatThrownBy(() -> reviewService.markHelpful(review.getReviewId(), testUserId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("본인의 리뷰");

        System.out.println("  [PASS] 셀프 도움돼요 → BusinessException");
    }

    @Test
    @DisplayName("markHelpful — 여러 사용자가 도움돼요 클릭 시 카운트 정확성")
    void markHelpful_multipleUsers_countAccuracy() {
        // Given
        Review review = reviewService.createReview(testUserId,
                reviewRequest(testUserId, testProductId, 5, "카운트 테스트", null));
        createdReviewIds.add(review.getReviewId());

        // 다른 사용자 3명 조회 (작성자 제외)
        List<Long> voters = jdbcTemplate.queryForList(
                "SELECT user_id FROM users WHERE user_id != ? AND is_active = true ORDER BY user_id LIMIT 3",
                Long.class, testUserId);

        if (voters.size() < 3) {
            System.out.println("  [SKIP] 투표자가 3명 미만");
            return;
        }

        // When: 3명이 도움돼요 클릭
        for (Long voterId : voters) {
            reviewService.markHelpful(review.getReviewId(), voterId);
        }

        // Then
        int helpfulCount = jdbcTemplate.queryForObject(
                "SELECT helpful_count FROM reviews WHERE review_id = ?",
                Integer.class, review.getReviewId());
        int recordCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM review_helpfuls WHERE review_id = ?",
                Integer.class, review.getReviewId());

        assertThat(helpfulCount).isEqualTo(3);
        assertThat(recordCount).isEqualTo(3);
        assertThat(helpfulCount).isEqualTo(recordCount); // 정합성

        System.out.println("  [PASS] 3명 도움돼요: count=" + helpfulCount + ", records=" + recordCount);
    }

    // ==================== 조회 ====================

    @Test
    @DisplayName("getProductReviews — 상품별 리뷰 페이징 조회")
    void getProductReviews_returnsPagedResults() {
        // Given: 리뷰 2개 생성
        Review r1 = reviewService.createReview(testUserId,
                reviewRequest(testUserId, testProductId, 5, "리뷰1", null));
        Review r2 = reviewService.createReview(otherUserId,
                reviewRequest(otherUserId, testProductId, 3, "리뷰2", null));
        createdReviewIds.add(r1.getReviewId());
        createdReviewIds.add(r2.getReviewId());

        // When
        Page<Review> page = reviewService.getProductReviews(testProductId, PageRequest.of(0, 10));

        // Then
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(2);

        System.out.println("  [PASS] 상품 리뷰 조회: " + page.getTotalElements() + "개");
    }

    @Test
    @DisplayName("getUserReviews — 사용자별 리뷰 조회")
    void getUserReviews_returnsUserReviews() {
        // Given
        Review review = reviewService.createReview(testUserId,
                reviewRequest(testUserId, testProductId, 4, "내 리뷰", null));
        createdReviewIds.add(review.getReviewId());

        // When
        Page<Review> page = reviewService.getUserReviews(testUserId, PageRequest.of(0, 10));

        // Then
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(1);
        assertThat(page.getContent()).anyMatch(r -> r.getReviewId().equals(review.getReviewId()));

        System.out.println("  [PASS] 사용자 리뷰 조회: " + page.getTotalElements() + "개");
    }

    @Test
    @DisplayName("getHelpedReviewIds — 도움돼요 누른 리뷰 ID 반환")
    void getHelpedReviewIds_returnsCorrectIds() {
        // Given
        Long anotherProductId = jdbcTemplate.queryForObject(
                "SELECT product_id FROM products WHERE is_active = true AND product_id <> ? ORDER BY product_id LIMIT 1",
                Long.class,
                testProductId
        );
        assertThat(anotherProductId).as("테스트용 다른 상품이 필요합니다.").isNotNull();

        Review r1 = reviewService.createReview(testUserId,
                reviewRequest(testUserId, testProductId, 5, "리뷰A", null));
        Review r2 = reviewService.createReview(testUserId,
                reviewRequest(testUserId, anotherProductId, 4, "리뷰B", null));
        createdReviewIds.add(r1.getReviewId());
        createdReviewIds.add(r2.getReviewId());

        // otherUserId가 r1에만 도움돼요
        reviewService.markHelpful(r1.getReviewId(), otherUserId);

        // When
        Set<Long> helpedIds = reviewService.getHelpedReviewIds(
                otherUserId, Set.of(r1.getReviewId(), r2.getReviewId()));

        // Then
        assertThat(helpedIds).contains(r1.getReviewId());
        assertThat(helpedIds).doesNotContain(r2.getReviewId());

        System.out.println("  [PASS] 도움돼요 ID 조회: " + helpedIds);
    }

    @Test
    @DisplayName("getHelpedReviewIds — null userId 또는 빈 Set → 빈 결과")
    void getHelpedReviewIds_edgeCases() {
        assertThat(reviewService.getHelpedReviewIds(null, Set.of(1L))).isEmpty();
        assertThat(reviewService.getHelpedReviewIds(testUserId, Set.of())).isEmpty();

        System.out.println("  [PASS] 엣지 케이스: null/빈 입력 → 빈 결과");
    }
}
