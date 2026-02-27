package com.shop.domain.product.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "product_images")
public class ProductImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "image_id")
    private Long imageId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "image_url", nullable = false, length = 500)
    private String imageUrl;

    @Column(name = "image_order", nullable = false)
    private Integer imageOrder;

    @Column(name = "is_thumbnail", nullable = false)
    private Boolean isThumbnail;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected ProductImage() {}

    /**
     * [P2-9] 상품 이미지 생성.
     *
     * @param product    소속 상품
     * @param imageUrl   이미지 URL
     * @param imageOrder 정렬 순서 (0: 대표 이미지, 1~N: 서브 이미지)
     * @param isThumbnail 대표 이미지 여부
     */
    public ProductImage(Product product, String imageUrl, int imageOrder, boolean isThumbnail) {
        this.product = product;
        this.imageUrl = imageUrl;
        this.imageOrder = imageOrder;
        this.isThumbnail = isThumbnail;
        this.createdAt = LocalDateTime.now();
    }

    public void updateOrder(int imageOrder) {
        this.imageOrder = imageOrder;
    }

    public void markAsThumbnail(boolean isThumbnail) {
        this.isThumbnail = isThumbnail;
    }

    public Long getImageId() { return imageId; }
    public Product getProduct() { return product; }
    public String getImageUrl() { return imageUrl; }
    public Integer getImageOrder() { return imageOrder; }
    public Boolean getIsThumbnail() { return isThumbnail; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
