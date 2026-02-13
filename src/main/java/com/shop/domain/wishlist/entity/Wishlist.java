package com.shop.domain.wishlist.entity;

import com.shop.domain.product.entity.Product;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "wishlists")
public class Wishlist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "wishlist_id")
    private Long wishlistId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Wishlist() {}

    public Wishlist(Long userId, Product product) {
        this.userId = userId;
        this.product = product;
        this.createdAt = LocalDateTime.now();
    }

    public Long getWishlistId() { return wishlistId; }
    public Long getUserId() { return userId; }
    public Product getProduct() { return product; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
