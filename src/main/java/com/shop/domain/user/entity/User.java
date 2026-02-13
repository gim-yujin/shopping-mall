package com.shop.domain.user.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "username", unique = true, nullable = false, length = 50)
    private String username;

    @Column(name = "email", unique = true, nullable = false, length = 100)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "role", nullable = false, length = 20)
    private String role;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tier_id", nullable = false)
    private UserTier tier;

    @Column(name = "total_spent", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalSpent;

    @Column(name = "point_balance", nullable = false)
    private Integer pointBalance;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    protected User() {}

    public User(String username, String email, String passwordHash, String name, String phone) {
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.name = name;
        this.phone = phone;
        this.role = "ROLE_USER";
        this.totalSpent = BigDecimal.ZERO;
        this.pointBalance = 0;
        this.isActive = true;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void updateLastLogin() {
        this.lastLoginAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void addTotalSpent(BigDecimal amount) {
        this.totalSpent = this.totalSpent.add(amount);
        if (this.totalSpent.compareTo(BigDecimal.ZERO) < 0) {
            this.totalSpent = BigDecimal.ZERO;
        }
        this.updatedAt = LocalDateTime.now();
    }

    public void addPoints(int points) {
        this.pointBalance += points;
        if (this.pointBalance < 0) {
            this.pointBalance = 0;
        }
        this.updatedAt = LocalDateTime.now();
    }

    public void usePoints(int points) {
        if (this.pointBalance < points) throw new IllegalArgumentException("포인트가 부족합니다.");
        this.pointBalance -= points;
        this.updatedAt = LocalDateTime.now();
    }

    public void updateTier(UserTier newTier) {
        this.tier = newTier;
        this.updatedAt = LocalDateTime.now();
    }

    public void updateProfile(String name, String phone, String email) {
        this.name = name;
        this.phone = phone;
        this.email = email;
        this.updatedAt = LocalDateTime.now();
    }

    public void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
        this.updatedAt = LocalDateTime.now();
    }

    public void setTier(UserTier tier) { this.tier = tier; }

    // Getters
    public Long getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getName() { return name; }
    public String getPhone() { return phone; }
    public String getRole() { return role; }
    public UserTier getTier() { return tier; }
    public BigDecimal getTotalSpent() { return totalSpent; }

    public void setTotalSpent(BigDecimal totalSpent) {
        this.totalSpent = totalSpent;
        this.updatedAt = LocalDateTime.now();
    }
    public Integer getPointBalance() { return pointBalance; }
    public Boolean getIsActive() { return isActive; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getLastLoginAt() { return lastLoginAt; }
}
