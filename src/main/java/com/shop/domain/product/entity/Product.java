package com.shop.domain.product.entity;

import com.shop.domain.category.entity.Category;
import jakarta.persistence.*;
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

    @OneToMany(mappedBy = "product", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @OrderBy("imageOrder ASC")
    private List<ProductImage> images = new ArrayList<>();

    protected Product() {}

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
    public List<ProductImage> getImages() { return images; }
}
