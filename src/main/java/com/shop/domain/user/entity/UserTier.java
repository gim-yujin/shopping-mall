package com.shop.domain.user.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_tiers")
public class UserTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "tier_id")
    private Integer tierId;

    @Column(name = "tier_name", nullable = false, length = 50)
    private String tierName;

    @Column(name = "tier_level", unique = true, nullable = false)
    private Integer tierLevel;

    @Column(name = "min_spent", nullable = false, precision = 15, scale = 2)
    private BigDecimal minSpent;

    @Column(name = "discount_rate", precision = 5, scale = 2)
    private BigDecimal discountRate;

    @Column(name = "point_earn_rate", precision = 5, scale = 2)
    private BigDecimal pointEarnRate;

    @Column(name = "free_shipping_threshold", precision = 10, scale = 2)
    private BigDecimal freeShippingThreshold;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    protected UserTier() {}

    // Getters
    public Integer getTierId() { return tierId; }
    public String getTierName() { return tierName; }
    public Integer getTierLevel() { return tierLevel; }
    public BigDecimal getMinSpent() { return minSpent; }
    public BigDecimal getDiscountRate() { return discountRate; }
    public BigDecimal getPointEarnRate() { return pointEarnRate; }
    public BigDecimal getFreeShippingThreshold() { return freeShippingThreshold; }
    public String getDescription() { return description; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
