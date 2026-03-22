package com.shop.domain.product.entity;

import com.shop.domain.category.entity.Category;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Product 엔티티 분기 커버리지 보강 테스트.
 *
 * <p>기존 ProductEntityUnitTest에서 다루지 않은 분기를 검증한다:
 * - create: 팩토리 메서드 기본값 초기화
 * - update: 모든 필드 갱신 + updatedAt 갱신
 * - toggleActive: isActive 토글 + updatedAt 갱신
 * - updateRating: ratingAvg/reviewCount 갱신
 * - getThumbnailUrl: 썸네일 이미지 존재/미존재 분기
 * - getDiscountPercent: originalPrice < price(마크업) 분기</p>
 */
class ProductEntityBranchTest {

    private Category mockCategory() {
        try {
            // Category는 protected 생성자만 가지므로 리플렉션으로 생성
            Constructor<Category> ctor = Category.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── create 팩토리 ──

    @Test
    @DisplayName("create — 팩토리 메서드로 생성 시 기본값 초기화")
    void create_initializesDefaults() {
        Category category = mockCategory();

        Product product = Product.create("테스트 상품", category, "설명",
                BigDecimal.valueOf(30000), BigDecimal.valueOf(35000), 100);

        assertThat(product.getProductName()).isEqualTo("테스트 상품");
        assertThat(product.getCategory()).isSameAs(category);
        assertThat(product.getDescription()).isEqualTo("설명");
        assertThat(product.getPrice()).isEqualByComparingTo("30000");
        assertThat(product.getOriginalPrice()).isEqualByComparingTo("35000");
        assertThat(product.getStockQuantity()).isEqualTo(100);
        assertThat(product.getSalesCount()).isEqualTo(0);
        assertThat(product.getViewCount()).isEqualTo(0);
        assertThat(product.getRatingAvg()).isEqualByComparingTo("0");
        assertThat(product.getReviewCount()).isEqualTo(0);
        assertThat(product.getIsActive()).isTrue();
        assertThat(product.getCreatedAt()).isNotNull();
        assertThat(product.getUpdatedAt()).isNotNull();
    }

    // ── update ──

    @Test
    @DisplayName("update — 상품 정보 갱신 + updatedAt 변경")
    void update_changesFieldsAndUpdatedAt() {
        Product product = Product.create("원래 상품", mockCategory(), "원래 설명",
                BigDecimal.valueOf(10000), BigDecimal.valueOf(15000), 50);
        var originalUpdatedAt = product.getUpdatedAt();

        Category newCategory = mockCategory();
        product.update("수정 상품", newCategory, "수정 설명",
                BigDecimal.valueOf(20000), BigDecimal.valueOf(25000), 200);

        assertThat(product.getProductName()).isEqualTo("수정 상품");
        assertThat(product.getCategory()).isSameAs(newCategory);
        assertThat(product.getDescription()).isEqualTo("수정 설명");
        assertThat(product.getPrice()).isEqualByComparingTo("20000");
        assertThat(product.getOriginalPrice()).isEqualByComparingTo("25000");
        assertThat(product.getStockQuantity()).isEqualTo(200);
        assertThat(product.getUpdatedAt()).isAfterOrEqualTo(originalUpdatedAt);
    }

    // ── toggleActive ──

    @Test
    @DisplayName("toggleActive — 활성→비활성 전환")
    void toggleActive_changesState() {
        Product product = Product.create("상품", mockCategory(), "",
                BigDecimal.valueOf(10000), null, 10);
        assertThat(product.getIsActive()).isTrue();

        product.toggleActive();
        assertThat(product.getIsActive()).isFalse();

        product.toggleActive();
        assertThat(product.getIsActive()).isTrue();
    }

    // ── updateRating ──

    @Test
    @DisplayName("updateRating — 평점과 리뷰 수 갱신")
    void updateRating_changesRatingAndCount() {
        Product product = Product.create("상품", mockCategory(), "",
                BigDecimal.valueOf(10000), null, 10);

        product.updateRating(BigDecimal.valueOf(4.5), 25);

        assertThat(product.getRatingAvg()).isEqualByComparingTo("4.5");
        assertThat(product.getReviewCount()).isEqualTo(25);
    }

    // ── getThumbnailUrl ──

    @Nested
    @DisplayName("getThumbnailUrl — 썸네일 이미지 분기")
    class ThumbnailUrlTests {

        @Test
        @DisplayName("이미지 리스트에 썸네일이 있으면 해당 URL 반환")
        void withThumbnail_returnsImageUrl() {
            Product product = Product.create("상품", mockCategory(), "",
                    BigDecimal.valueOf(10000), null, 10);

            // 이미지 추가 (images 리스트에 직접 추가)
            ProductImage thumbnail = new ProductImage(product, "/img/thumb.jpg", 0, true);
            ProductImage sub = new ProductImage(product, "/img/sub.jpg", 1, false);
            product.getImages().add(thumbnail);
            product.getImages().add(sub);

            assertThat(product.getThumbnailUrl()).isEqualTo("/img/thumb.jpg");
        }

        @Test
        @DisplayName("이미지 리스트에 썸네일이 없으면 플레이스홀더 반환")
        void withoutThumbnail_returnsPlaceholder() {
            Product product = Product.create("상품", mockCategory(), "",
                    BigDecimal.valueOf(10000), null, 10);

            // 썸네일이 아닌 이미지만 추가
            ProductImage sub = new ProductImage(product, "/img/sub.jpg", 0, false);
            product.getImages().add(sub);

            assertThat(product.getThumbnailUrl()).isEqualTo("/images/product-placeholder.svg");
        }

        @Test
        @DisplayName("이미지 리스트가 비어있으면 플레이스홀더 반환")
        void emptyImages_returnsPlaceholder() {
            Product product = Product.create("상품", mockCategory(), "",
                    BigDecimal.valueOf(10000), null, 10);

            // 이미지 없음 (images는 빈 ArrayList)
            assertThat(product.getThumbnailUrl()).isEqualTo("/images/product-placeholder.svg");
        }
    }

    // ── getDiscountPercent: 마크업(originalPrice < price) ──

    @Test
    @DisplayName("getDiscountPercent — originalPrice < price(마크업)이면 0 반환")
    void getDiscountPercent_markup_returnsZero() {
        Product product = Product.create("상품", mockCategory(), "",
                BigDecimal.valueOf(20000), BigDecimal.valueOf(15000), 10);

        // originalPrice(15000) < price(20000) → 0
        assertThat(product.getDiscountPercent()).isEqualTo(0);
    }

    @Test
    @DisplayName("getDiscountPercent — originalPrice == 0이면 0 반환")
    void getDiscountPercent_zeroOriginalPrice_returnsZero() {
        Product product = Product.create("상품", mockCategory(), "",
                BigDecimal.valueOf(10000), BigDecimal.ZERO, 10);

        assertThat(product.getDiscountPercent()).isEqualTo(0);
    }
}
