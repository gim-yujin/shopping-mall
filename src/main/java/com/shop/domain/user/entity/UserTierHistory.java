package com.shop.domain.user.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_tier_history")
public class UserTierHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "history_id")
    private Long historyId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "from_tier_id")
    private Integer fromTierId;

    @Column(name = "to_tier_id", nullable = false)
    private Integer toTierId;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    @Column(name = "reason", length = 100)
    private String reason;

    protected UserTierHistory() {}

    public UserTierHistory(Long userId, Integer fromTierId, Integer toTierId, String reason) {
        this.userId = userId;
        this.fromTierId = fromTierId;
        this.toTierId = toTierId;
        this.reason = reason;
        this.changedAt = LocalDateTime.now();
    }

    public Long getHistoryId() { return historyId; }
    public Long getUserId() { return userId; }
    public Integer getFromTierId() { return fromTierId; }
    public Integer getToTierId() { return toTierId; }
    public LocalDateTime getChangedAt() { return changedAt; }
    public String getReason() { return reason; }
}
