package com.shop.domain.user.dto;

import com.shop.domain.user.entity.User;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record UserProfileResponse(
        Long userId,
        String username,
        String email,
        String name,
        String phone,
        String tierName,
        int tierLevel,
        BigDecimal totalSpent,
        int pointBalance,
        LocalDateTime createdAt
) {
    public static UserProfileResponse from(User user) {
        return new UserProfileResponse(
                user.getUserId(),
                user.getUsername(),
                user.getEmail(),
                user.getName(),
                user.getPhone(),
                user.getTier() != null ? user.getTier().getTierName() : null,
                user.getTier() != null ? user.getTier().getTierLevel() : 0,
                user.getTotalSpent(),
                user.getPointBalance(),
                user.getCreatedAt()
        );
    }
}
