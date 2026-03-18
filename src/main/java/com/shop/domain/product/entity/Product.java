package com.shop.domain.product.entity;

import com.shop.domain.category.entity.Category;
import jakarta.persistence.*;
import org.hibernate.Hibernate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "original_price", precision = 12, scale = 2)
    private BigDecimal originalPrice;

    @Column(name = "stock_quantity", nullable = false)
    private Integer stockQuantity;

    @Column(name = "sales_count", nullable = false)
    private Integer salesCount;

    @Column(name = "view_count", nullable = false)
    private Integer viewCount;

    @Column(name = "rating_avg", nullable = false, precision = 3, scale = 2)
    private BigDecimal ratingAvg;

    @Column(name = "review_count", nullable = false)
    private Integer reviewCount;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * [Phase 4] 낙관적 잠금(Optimistic Locking) 버전 필드.
     *
     * <p><b>문제:</b> 관리자 상품 수정(가격, 설명, 카테고리 변경 등)에 동시성 제어가 없었다.
     * 두 관리자가 동시에 같은 상품을 수정하면 나중에 저장한 쪽이 먼저 저장한 변경을
     * 무음으로 덮어쓴다(Lost Update). 또한 관리자가 상품을 로드한 뒤 다수의 주문으로
     * 재고가 변경된 상태에서 저장하면, 변경된 재고 상태를 인지하지 못할 수 있다.</p>
     *
     * <p><b>해결:</b> JPA @Version으로 낙관적 잠금을 적용한다.
     * UPDATE 시 WHERE version = ? 조건이 추가되어, 로드 시점 이후 다른 트랜잭션이
     * 엔티티를 수정했으면 {@code OptimisticLockException}이 발생한다.</p>
     *
     * <p><b>비관적 잠금과의 공존:</b> 주문 시 재고 차감은 {@code findByIdWithLock()}
     * (PESSIMISTIC_WRITE)을 사용한다. 비관적 잠금이 행을 선점하므로 동일 트랜잭션 내에서는
     * @Version 충돌이 발생하지 않는다. 두 전략은 용도가 다르다:
     * <ul>
     *   <li>비관적 잠금 → 재고 차감(높은 경합, 실패 불허)</li>
     *   <li>낙관적 잠금 → 관리자 상품 편집(낮은 경합, 충돌 감지 후 재시도 유도)</li>
     * </ul></p>
     */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @OneToMany(mappedBy = "product", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @OrderBy("imageOrder ASC")
    private List<ProductImage> images = new ArrayList<>();

    protected Product() {}

    /**
     * 관리자 상품 등록용 팩토리 메서드.
     */
    public static Product create(String productName, Category category, String description,
                                 BigDecimal price, BigDecimal originalPrice, int stockQuantity) {
        Product p = new Product();
        p.productName = productName;
        p.category = category;
        p.description = description;
        p.price = price;
        p.originalPrice = originalPrice;
        p.stockQuantity = stockQuantity;
        p.salesCount = 0;
        p.viewCount = 0;
        p.ratingAvg = BigDecimal.ZERO;
        p.reviewCount = 0;
        p.isActive = true;
        p.createdAt = LocalDateTime.now();
        p.updatedAt = LocalDateTime.now();
        return p;
    }

    /**
     * 관리자 상품 수정.
     */
    public void update(String productName, Category category, String description,
                       BigDecimal price, BigDecimal originalPrice, int stockQuantity) {
        this.productName = productName;
        this.category = category;
        this.description = description;
        this.price = price;
        this.originalPrice = originalPrice;
        this.stockQuantity = stockQuantity;
        this.updatedAt = LocalDateTime.now();
    }

    public void toggleActive() {
        this.isActive = !this.isActive;
        this.updatedAt = LocalDateTime.now();
    }

    public void incrementViewCount() {
        this.viewCount++;
    }

    public void decreaseStock(int quantity) {
        if (this.stockQuantity < quantity) {
            throw new IllegalStateException("재고가 부족합니다.");
        }
        this.stockQuantity -= quantity;
        this.salesCount += quantity;
        this.updatedAt = LocalDateTime.now();
    }

    public void increaseStock(int quantity) {
        this.stockQuantity += quantity;
        this.updatedAt = LocalDateTime.now();
    }

    public void increaseStockAndRollbackSales(int quantity) {
        if (this.salesCount < quantity) {
            throw new IllegalStateException("판매량은 0보다 작아질 수 없습니다.");
        }
        this.stockQuantity += quantity;
        this.salesCount -= quantity;
        this.updatedAt = LocalDateTime.now();
    }

    public void updateRating(BigDecimal newAvg, int newCount) {
        this.ratingAvg = newAvg;
        this.reviewCount = newCount;
        this.updatedAt = LocalDateTime.now();
    }

    public int getDiscountPercent() {
        if (originalPrice != null && originalPrice.compareTo(BigDecimal.ZERO) > 0
            && originalPrice.compareTo(price) > 0) {
            return originalPrice.subtract(price)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(originalPrice, 0, java.math.RoundingMode.FLOOR)
                    .intValue();
        }
        return 0;
    }

    public String getThumbnailUrl() {
        if (!Hibernate.isInitialized(images) || images == null) {
            return "/images/product-placeholder.svg";
        }

        return images.stream()
                .filter(ProductImage::getIsThumbnail)
                .findFirst()
                .map(ProductImage::getImageUrl)
                .orElse("/images/product-placeholder.svg");
    }

    // Getters
    public Long getProductId() { return productId; }
    public String getProductName() { return productName; }
    public Category getCategory() { return category; }
    public String getDescription() { return description; }
    public BigDecimal getPrice() { return price; }
    public BigDecimal getOriginalPrice() { return originalPrice; }
    public Integer getStockQuantity() { return stockQuantity; }
    public Integer getSalesCount() { return salesCount; }
    public Integer getViewCount() { return viewCount; }
    public BigDecimal getRatingAvg() { return ratingAvg; }
    public Integer getReviewCount() { return reviewCount; }
    public Boolean getIsActive() { return isActive; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public Integer getVersion() { return version; }
    public List<ProductImage> getImages() { return images; }
}
