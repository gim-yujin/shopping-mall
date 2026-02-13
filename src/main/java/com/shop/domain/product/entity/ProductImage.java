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

    public Long getImageId() { return imageId; }
    public Product getProduct() { return product; }
    public String getImageUrl() { return imageUrl; }
    public Integer getImageOrder() { return imageOrder; }
    public Boolean getIsThumbnail() { return isThumbnail; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
